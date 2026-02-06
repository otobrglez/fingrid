package fingrid.service.auth

import zio.*
import zio.hibernate.Hibernate
import zio.hibernate.syntax.*
import fingrid.persistence.entities.User
import at.favre.lib.crypto.bcrypt.BCrypt

final case class AuthService(
  private val jwtService: JwtService
):
  private val bcryptHasher   = BCrypt.withDefaults()
  private val bcryptVerifier = BCrypt.verifyer()

  def register(request: RegisterRequest): RIO[Hibernate, AuthResponse] = Hibernate.inTransaction: session =>
    for
      _            <- validateEmail(request.email) <&> validatePassword(request.password)
      existingUser <- ZIO.attempt {
                        val query = session.createQuery[User]("SELECT u FROM User u WHERE u.email = :email")
                        query.setParameter("email", request.email)
                        query.getResultList.size() > 0
                      }
      _            <- ZIO.when(existingUser)(ZIO.fail(new Exception("Email already exists")))
      passwordHash <- ZIO.attempt(bcryptHasher.hashToString(10, request.password.toCharArray))
      user          = new User(request.name, request.email, request.rgbHashColor, passwordHash)
      _            <- ZIO.attempt {
                        session.persist(user)
                        session.flush()
                      }
      token        <- jwtService.generateToken(user.id, user.email)
    yield AuthResponse(token, user.id, user.email, user.name)

  def login(request: LoginRequest): RIO[Hibernate, AuthResponse] = Hibernate.inTransaction: session =>
    for
      userOpt <- ZIO.attempt {
                   val query   = session.createQuery[User]("SELECT u FROM User u WHERE u.email = :email")
                   query.setParameter("email", request.email)
                   val results = query.getResultList
                   if results.size() > 0 then Some(results.get(0)) else None
                 }
      user    <- ZIO.fromOption(userOpt).orElseFail(new Exception("Invalid credentials"))
      _       <- ZIO.attempt {
                   val result = bcryptVerifier.verify(request.password.toCharArray, user.passwordHash)
                   if !result.verified then throw new Exception("Invalid credentials")
                 }
      token   <- jwtService.generateToken(user.id, user.email)
    yield AuthResponse(token, user.id, user.email, user.name)

  def getUserById(userId: Long): RIO[Hibernate, Option[User]] =
    Hibernate.attemptInTransaction(_.maybeFind[User](userId))

  private def validateEmail(email: String): Task[Unit] =
    ZIO
      .when(!email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$"))(ZIO.fail(new Exception("Invalid email format")))
      .unit

  private def validatePassword(password: String): Task[Unit] =
    ZIO
      .when(password.length < 8)(ZIO.fail(new Exception("Password must be at least 8 characters")))
      .unit

object AuthService:
  def register(request: RegisterRequest): RIO[Hibernate & AuthService, AuthResponse] =
    ZIO.serviceWithZIO[AuthService](_.register(request))

  def login(request: LoginRequest): RIO[Hibernate & AuthService, AuthResponse] =
    ZIO.serviceWithZIO[AuthService](_.login(request))

  def getUserById(userId: Long): RIO[Hibernate & AuthService, Option[User]] =
    ZIO.serviceWithZIO[AuthService](_.getUserById(userId))

  val live: URLayer[JwtService, AuthService] =
    ZLayer.fromFunction((jwtService: JwtService) => AuthService(jwtService))
