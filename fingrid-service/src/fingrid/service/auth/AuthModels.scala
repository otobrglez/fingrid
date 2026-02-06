package fingrid.service.auth

import zio.schema.{DeriveSchema, Schema}
import zio.json.{JsonDecoder, JsonEncoder}

final case class LoginRequest(
  email: String,
  password: String
)

object LoginRequest:
  implicit val schema: Schema[LoginRequest] = DeriveSchema.gen[LoginRequest]
  implicit val encoder: JsonEncoder[LoginRequest] = zio.schema.codec.JsonCodec.jsonEncoder(schema)
  implicit val decoder: JsonDecoder[LoginRequest] = zio.schema.codec.JsonCodec.jsonDecoder(schema)

final case class RegisterRequest(
  name: String,
  email: String,
  password: String,
  rgbHashColor: String
)

object RegisterRequest:
  implicit val schema: Schema[RegisterRequest] = DeriveSchema.gen[RegisterRequest]
  implicit val encoder: JsonEncoder[RegisterRequest] = zio.schema.codec.JsonCodec.jsonEncoder(schema)
  implicit val decoder: JsonDecoder[RegisterRequest] = zio.schema.codec.JsonCodec.jsonDecoder(schema)

final case class AuthResponse(
  token: String,
  userId: Long,
  email: String,
  name: String
)

object AuthResponse:
  implicit val schema: Schema[AuthResponse] = DeriveSchema.gen[AuthResponse]
  implicit val encoder: JsonEncoder[AuthResponse] = zio.schema.codec.JsonCodec.jsonEncoder(schema)
  implicit val decoder: JsonDecoder[AuthResponse] = zio.schema.codec.JsonCodec.jsonDecoder(schema)

final case class JwtPayload(
  userId: Long,
  email: String
)

object JwtPayload:
  implicit val schema: Schema[JwtPayload] = DeriveSchema.gen[JwtPayload]
  implicit val encoder: JsonEncoder[JwtPayload] = zio.schema.codec.JsonCodec.jsonEncoder(schema)
  implicit val decoder: JsonDecoder[JwtPayload] = zio.schema.codec.JsonCodec.jsonDecoder(schema)
