package zio.hibernate

import jakarta.persistence.EntityManagerFactory
import org.hibernate.jpa.HibernatePersistenceConfiguration
import org.hibernate.{Session, SessionFactory, Transaction}
import zio.*

sealed trait HibernateError extends Throwable

object HibernateError:
  final case class SessionFactoryUnwrapError(message: String, cause: Throwable)
      extends IllegalArgumentException(message, cause)
      with HibernateError

  final case class NoActiveTransactionError(message: String) extends IllegalStateException(message) with HibernateError

  final case class TransactionError(message: String, cause: Throwable)
      extends RuntimeException(message, cause)
      with HibernateError

private case class TransactionContext(session: Session, transaction: Transaction)

final class Hibernate(
  private val sessionFactory: SessionFactory,
  private val txContext: FiberRef[Option[TransactionContext]]
):

  private def transactional[R, A](f: (Session, Transaction) => RIO[R, A]): RIO[R, A] =
    ZIO.acquireReleaseExitWith(
      acquire = ZIO.attempt {
        val session = sessionFactory.openSession()
        val tx      = session.beginTransaction()
        session -> tx
      }
    ) {
      case (session, tx) -> Exit.Success(_) =>
        ZIO.attempt {
          if tx.isActive then tx.commit()
          session.close()
        }.catchAll(err => ZIO.logError(s"Error committing transaction: ${err.getMessage}") *> ZIO.die(err))

      case (session, tx) -> Exit.Failure(cause) =>
        ZIO.logWarning(s"Transaction rolled back: ${cause.prettyPrint}") *>
          ZIO.attempt {
            if tx.isActive then tx.rollback()
            session.close()
          }.tapError(rollbackErr =>
            ZIO.logError(s"Error during rollback: ${rollbackErr.getMessage}. Original failure: ${cause.prettyPrint}")
          ).ignore
    } { case (session, tx) => f(session, tx) }

  def readOnly[R, A](action: Session => RIO[R, A]): RIO[R, A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.attempt {
        val session = sessionFactory.openSession()
        session.setDefaultReadOnly(true)
        session.setHibernateFlushMode(org.hibernate.FlushMode.MANUAL)
        val tx      = session.beginTransaction()
        session -> tx
      }
    ) { case (session, tx) =>
      ZIO.attempt {
        if tx.isActive then tx.rollback() // rollback for read-only, don't commit
        session.close()
      }.ignore
    } { case (session, _) => action(session) }

  def inStatelessSession[R, A](f: org.hibernate.StatelessSession => RIO[R, A]): RIO[R, A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.attempt(sessionFactory.openStatelessSession())
    )(session => ZIO.attempt(session.close()).ignore)(f)

  def withFlushMode[R, A](mode: org.hibernate.FlushMode)(f: Session => RIO[R, A]): RIO[R, A] = inTransaction: session =>
    ZIO.acquireReleaseWith(
      acquire = ZIO.attempt {
        val originalMode = session.getHibernateFlushMode
        session.setHibernateFlushMode(mode)
        originalMode
      }
    )(originalMode => ZIO.attempt(session.setHibernateFlushMode(originalMode)).ignore) { _ =>
      f(session)
    }

  def inBatch[R, A](batchSize: Int)(f: Session => RIO[R, Chunk[A]]): RIO[R, Chunk[A]] = inTransaction: session =>
    session.setJdbcBatchSize(batchSize)
    f(session).zipLeft(ZIO.attempt(session.flush()))

  def inBatchWithClear[R, A](batchSize: Int, clearEvery: Int)(
    f: Session => RIO[R, Chunk[A]]
  ): RIO[R, Chunk[A]] = inTransaction: session =>
    session.setJdbcBatchSize(batchSize)
    val originalFlushMode = session.getHibernateFlushMode
    session.setHibernateFlushMode(org.hibernate.FlushMode.MANUAL)

    f(session).tap { chunk =>
      ZIO.attempt {
        session.flush()
        if chunk.size >= clearEvery then session.clear()
      }
    }.zipLeft(ZIO.attempt(session.setHibernateFlushMode(originalFlushMode)))

  def batchPersist[R, A <: AnyRef](entities: Chunk[A], batchSize: Int = 50): RIO[R, Unit] = inTransaction: session =>
    session.setJdbcBatchSize(batchSize)
    ZIO
      .foreachDiscard(entities.zipWithIndex) { case (entity, idx) =>
        ZIO.attempt {
          session.persist(entity)
          if (idx + 1) % batchSize == 0 then
            session.flush()
            session.clear()
        }
      }
      .zipLeft(ZIO.attempt {
        session.flush()
        session.clear()
      })

  def attemptInTransaction[A](action: Session => A): Task[A] =
    inTransaction(session => ZIO.attempt(action(session)))

  def inTransaction[R, A](action: Session => RIO[R, A]): RIO[R, A] = txContext.get.flatMap:
    case Some(ctx) =>
      ZIO.logDebug("Propagating existing transaction") *> action(ctx.session)

    case None =>
      transactional((session, tx) => txContext.locally(Some(TransactionContext(session, tx)))(action(session)))

  def withTransaction[R, A](action: (Session, Transaction) => RIO[R, A]): RIO[R, A] = txContext.get.flatMap:
    case Some(ctx) =>
      ZIO.logDebug("Propagating existing transaction") *> action(ctx.session, ctx.transaction)
    case None      =>
      transactional((session, tx) => txContext.locally(Some(TransactionContext(session, tx)))(action(session, tx)))

  def flush: RIO[Any, Unit] = txContext.get.flatMap:
    case Some(ctx) => ZIO.attempt(ctx.session.flush())
    case None      => ZIO.fail(HibernateError.NoActiveTransactionError("No active transaction to flush"))

  def getSessionFactory: UIO[SessionFactory] = ZIO.succeed(sessionFactory)

  def getStatistics: UIO[org.hibernate.stat.Statistics] =
    ZIO.succeed(sessionFactory.getStatistics)

  def statisticsEnabled: UIO[Boolean] =
    ZIO.succeed(sessionFactory.getStatistics.isStatisticsEnabled)

  def withSchemaManager[R, A](f: org.hibernate.relational.SchemaManager => RIO[R, A]): RIO[R, A] =
    ZIO.attempt(sessionFactory.getSchemaManager).flatMap(f)

object Hibernate:

  def sessionFactory: URIO[Hibernate, SessionFactory] =
    ZIO.serviceWith[Hibernate](_.sessionFactory)

  def attemptInTransaction[R, A](action: Session => A): RIO[Hibernate, A] =
    ZIO.serviceWithZIO[Hibernate](_.attemptInTransaction(action))

  def inTransaction[R, A](f: Session => RIO[R, A]): RIO[R & Hibernate, A] =
    ZIO.serviceWithZIO[Hibernate](_.inTransaction(f))

  def readOnly[R, A](f: Session => RIO[R, A]): RIO[R & Hibernate, A] =
    ZIO.serviceWithZIO[Hibernate](_.readOnly(f))

  def withTransaction[R, A](f: (Session, Transaction) => RIO[R, A]): RIO[R & Hibernate, A] =
    ZIO.serviceWithZIO[Hibernate](_.withTransaction(f))

  def inStatelessSession[R, A](f: org.hibernate.StatelessSession => RIO[R, A]): RIO[R & Hibernate, A] =
    ZIO.serviceWithZIO[Hibernate](_.inStatelessSession(f))

  def withFlushMode[R, A](mode: org.hibernate.FlushMode)(f: Session => RIO[R, A]): RIO[R & Hibernate, A] =
    ZIO.serviceWithZIO[Hibernate](_.withFlushMode(mode)(f))

  def inBatch[R, A](batchSize: Int)(f: Session => RIO[R, Chunk[A]]): RIO[R & Hibernate, Chunk[A]] =
    ZIO.serviceWithZIO[Hibernate](_.inBatch(batchSize)(f))

  def inBatchWithClear[R, A](batchSize: Int, clearEvery: Int)(
    f: Session => RIO[R, Chunk[A]]
  ): RIO[R & Hibernate, Chunk[A]] =
    ZIO.serviceWithZIO[Hibernate](_.inBatchWithClear(batchSize, clearEvery)(f))

  def batchPersist[R, A <: AnyRef](entities: Chunk[A], batchSize: Int = 50): RIO[R & Hibernate, Unit] =
    ZIO.serviceWithZIO[Hibernate](_.batchPersist(entities, batchSize))

  def statistics: URIO[Hibernate, org.hibernate.stat.Statistics] =
    ZIO.serviceWithZIO[Hibernate](_.getStatistics)

  def statisticsEnabled: URIO[Hibernate, Boolean] =
    ZIO.serviceWithZIO[Hibernate](_.statisticsEnabled)

  def withSchemaManager[R, A](f: org.hibernate.relational.SchemaManager => RIO[R, A]): RIO[R & Hibernate, A] =
    ZIO.serviceWithZIO[Hibernate](_.withSchemaManager(f))

  def flush: RIO[Hibernate, Unit] =
    ZIO.serviceWithZIO[Hibernate](_.flush)

  def liveWithEntityManagerFactory(factory: => EntityManagerFactory): RLayer[Scope, Hibernate] = ZLayer.scoped:
    for
      emf       <- ZIO.fromAutoCloseable(ZIO.attempt(factory))
      sf        <-
        ZIO
          .attempt(emf.unwrap(classOf[SessionFactory]))
          .catchAll(err =>
            ZIO.fail(
              HibernateError.SessionFactoryUnwrapError(
                s"EntityManagerFactory must be backed by Hibernate SessionFactory. Unable to unwrap: ${err.getMessage}",
                err
              )
            )
          )
      txContext <- FiberRef.make[Option[TransactionContext]](None)
    yield new Hibernate(sf, txContext)

  def liveWithSessionFactory(factory: => SessionFactory): RLayer[Scope, Hibernate] = ZLayer.scoped:
    for
      sf        <- ZIO.fromAutoCloseable(ZIO.attempt(factory))
      txContext <- FiberRef.make[Option[TransactionContext]](None)
    yield new Hibernate(sf, txContext)

  def liveWithConfiguration(config: => HibernatePersistenceConfiguration): RLayer[Scope, Hibernate] = ZLayer.scoped:
    for
      emf       <- ZIO.fromAutoCloseable(ZIO.attempt(config.createEntityManagerFactory()))
      sf        <-
        ZIO
          .attempt(emf.unwrap(classOf[SessionFactory]))
          .catchAll(err =>
            ZIO.fail(
              HibernateError.SessionFactoryUnwrapError(
                s"Configuration must produce Hibernate-backed EntityManagerFactory. Unable to unwrap: ${err.getMessage}",
                err
              )
            )
          )
      txContext <- FiberRef.make[Option[TransactionContext]](None)
    yield new Hibernate(sf, txContext)

  def live: RLayer[Scope & EntityManagerFactory, Hibernate] = ZLayer.scoped:
    ZIO.serviceWithZIO[EntityManagerFactory] { emf =>
      for
        sf        <-
          ZIO
            .attempt(emf.unwrap(classOf[SessionFactory]))
            .catchAll(err =>
              ZIO.fail(
                HibernateError.SessionFactoryUnwrapError(
                  s"EntityManagerFactory must be backed by Hibernate SessionFactory. Unable to unwrap: ${err.getMessage}",
                  err
                )
              )
            )
        txContext <- FiberRef.make[Option[TransactionContext]](None)
      yield new Hibernate(sf, txContext)
    }
