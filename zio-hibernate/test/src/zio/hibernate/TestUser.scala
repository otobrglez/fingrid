package zio.hibernate

import jakarta.persistence.{Column, Entity, GeneratedValue, GenerationType, Id, Table}
import scala.compiletime.uninitialized

@Entity
@Table(name = "test_users")
class TestUser:
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  var id: java.lang.Long = uninitialized

  @Column(nullable = false)
  var name: String = uninitialized

  @Column(nullable = false, unique = true)
  var email: String = uninitialized

  def this(name: String, email: String) =
    this()
    this.name = name
    this.email = email

object TestUser:
  def apply(name: String, email: String): TestUser =
    new TestUser(name, email)
