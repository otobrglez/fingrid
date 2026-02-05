package zio

import org.hibernate.Session
import org.hibernate.query.Query

import scala.reflect.ClassTag

package object hibernate:

  object syntax:
    extension (s: Session)
      def maybeFind[T](klass: Class[T], id: Any): Option[T] = Option(s.find(klass, id))

      def maybeFind[T](id: Any)(using m: ClassTag[T]): Option[T] = maybeFind(m.runtimeClass.asInstanceOf[Class[T]], id)

      def attemptFind[T](klass: Class[T], id: Any): Task[Option[T]] = ZIO.attempt(maybeFind[T](klass, id))

      def attemptFind[T](id: Any)(using m: ClassTag[T]): Task[Option[T]] =
        attemptFind(m.runtimeClass.asInstanceOf[Class[T]], id)

      def createQuery[T](queryString: String)(using m: ClassTag[T]): Query[T] =
        s.createQuery(queryString, m.runtimeClass.asInstanceOf[Class[T]])

      def attemptPersist[T <: AnyRef](entity: T): Task[Unit] = ZIO.attempt(s.persist(entity))
        
    
