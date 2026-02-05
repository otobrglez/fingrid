package zio.hibernate

import jakarta.persistence.EntityManagerFactory
import org.hibernate.jpa.HibernatePersistenceConfiguration
import org.hibernate.{Session, SessionFactory, Transaction}
import zio.*

private case class TransactionContext(session: Session, transaction: Transaction, isNested: Boolean)

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
        (ZIO.logWarning(s"Transaction rolled back: ${cause.prettyPrint}") *>
          ZIO.attempt {
            if tx.isActive then tx.rollback()
            session.close()
          }).catchAll(err => ZIO.logError(s"Error during rollback: ${err.getMessage}")).ignore
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
        if tx.isActive then tx.commit()
        session.close()
      }.ignore
    } { case (session, _) => action(session) }

  def inStatelessSession[R, A](f: org.hibernate.StatelessSession => RIO[R, A]): RIO[R, A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.attempt(sessionFactory.openStatelessSession())
    )(session => ZIO.attempt(session.close()).ignore)(f)

  def withFlushMode[R, A](mode: org.hibernate.FlushMode)(f: Session => RIO[R, A]): RIO[R, A] =
    inTransaction { session =>
      ZIO.attempt(session.setHibernateFlushMode(mode)) *> f(session)
    }

  def inBatch[R, A](batchSize: Int)(f: Session => RIO[R, Chunk[A]]): RIO[R, Chunk[A]] =
    inTransaction { session =>
      session.setJdbcBatchSize(batchSize)
      f(session).zipLeft(ZIO.attempt(session.flush()))
    }

  def inTransaction[R, A](action: Session => RIO[R, A]): RIO[R, A] = txContext.get.flatMap:
    case Some(ctx) =>
      ZIO.logDebug("Propagating existing transaction") *>
        txContext.locally(Some(ctx.copy(isNested = true)))(action(ctx.session))

    case None =>
      transactional { (session, tx) =>
        txContext.locally(Some(TransactionContext(session, tx, isNested = false)))(action(session))
      }

  def withTransaction[R, A](action: (Session, Transaction) => RIO[R, A]): RIO[R, A] =
    txContext.get.flatMap:
      case Some(ctx) =>
        ZIO.logDebug("Propagating existing transaction") *>
          txContext.locally(Some(ctx.copy(isNested = true)))(action(ctx.session, ctx.transaction))
      case None      =>
        transactional { (session, tx) =>
          txContext.locally(Some(TransactionContext(session, tx, isNested = false)))(action(session, tx))
        }

  def flush: RIO[Any, Unit] =
    txContext.get.flatMap:
      case Some(ctx) => ZIO.attempt(ctx.session.flush())
      case None      => ZIO.fail(new IllegalStateException("No active transaction to flush"))

  def getSessionFactory: UIO[SessionFactory] = ZIO.succeed(sessionFactory)

  def getStatistics: UIO[org.hibernate.stat.Statistics] =
    ZIO.succeed(sessionFactory.getStatistics)

  def withSchemaManager[R, A](f: org.hibernate.relational.SchemaManager => RIO[R, A]): RIO[R, A] =
    ZIO.attempt(sessionFactory.getSchemaManager).flatMap(f)

object Hibernate:

  def sessionFactory: URIO[Hibernate, SessionFactory] =
    ZIO.serviceWith[Hibernate](_.sessionFactory)

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

  def statistics: URIO[Hibernate, org.hibernate.stat.Statistics] =
    ZIO.serviceWithZIO[Hibernate](_.getStatistics)

  def withSchemaManager[R, A](f: org.hibernate.relational.SchemaManager => RIO[R, A]): RIO[R & Hibernate, A] =
    ZIO.serviceWithZIO[Hibernate](_.withSchemaManager(f))

  def flush: RIO[Hibernate, Unit] =
    ZIO.serviceWithZIO[Hibernate](_.flush)

  def liveWithEntityManagerFactory(factory: => EntityManagerFactory): RLayer[Scope, Hibernate] = ZLayer.scoped:
    for
      emf       <- ZIO.fromAutoCloseable(ZIO.attempt(factory))
      txContext <- FiberRef.make[Option[TransactionContext]](None)
    yield new Hibernate(emf.unwrap(classOf[SessionFactory]), txContext)

  def liveWithSessionFactory(factory: => SessionFactory): RLayer[Scope, Hibernate] = ZLayer.scoped:
    for
      sf        <- ZIO.fromAutoCloseable(ZIO.attempt(factory))
      txContext <- FiberRef.make[Option[TransactionContext]](None)
    yield new Hibernate(sf, txContext)

  def liveWithConfiguration(config: => HibernatePersistenceConfiguration): RLayer[Scope, Hibernate] =
    liveWithSessionFactory(config.createEntityManagerFactory().unwrap(classOf[SessionFactory]))

  def live: RLayer[Scope & EntityManagerFactory, Hibernate] = ZLayer.scoped:
    ZIO.serviceWithZIO[EntityManagerFactory] { emf =>
      FiberRef.make[Option[TransactionContext]](None).map { txContext =>
        new Hibernate(emf.unwrap(classOf[SessionFactory]), txContext)
      }
    }
