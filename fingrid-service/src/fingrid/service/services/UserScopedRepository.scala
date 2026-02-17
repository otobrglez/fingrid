package fingrid.service.services

import fingrid.service.authentication.AuthUser
import fingrid.persistence.entities.User
import zio.RIO
import zio.hibernate.Hibernate

trait UserScopedRepository[T, R]:
  final def findByAuth(authUser: AuthUser): RIO[R & Hibernate, List[T]] = findByUser(authUser.userID)
  final def findByUser(user: User): RIO[R & Hibernate, List[T]]         = findByUser(user.id)
  def findByUser(id: UserID): RIO[R & Hibernate, List[T]]
