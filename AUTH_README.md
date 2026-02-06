# Authentication System

A complete JWT-based authentication system for the Fingrid backend API.

## Features

- **User Registration** - Register new users with email/password
- **User Login** - Authenticate users and receive JWT tokens
- **Protected Routes** - Example of JWT-protected endpoints
- **Password Hashing** - BCrypt password hashing using Spring Security Crypto
- **JWT Tokens** - Stateless authentication with configurable expiration

## Environment Variables

```bash
# Database Configuration (existing)
POSTGRES_USER=fingrid
POSTGRES_PASSWORD=yourpassword
DATABASE_URL=jdbc:postgresql://localhost:5432/fingrid

# JWT Configuration (new)
JWT_SECRET=your-secret-key-here-change-this-in-production
JWT_EXPIRATION_SECONDS=86400  # Optional, defaults to 24 hours

# Server Configuration
HTTP_PORT=8080  # Optional, defaults to 8080
```

## API Endpoints

### POST /auth/register
Register a new user.

**Request Body:**
```json
{
  "name": "John Doe",
  "email": "john@example.com",
  "password": "yourpassword123",
  "rgbHashColor": "#FF5733"
}
```

**Response (201):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": 1,
  "email": "john@example.com",
  "name": "John Doe"
}
```

**Errors:**
- `409 Conflict` - Email already exists
- `400 Bad Request` - Validation errors (invalid email, password too short)

### POST /auth/login
Authenticate a user and get a JWT token.

**Request Body:**
```json
{
  "email": "john@example.com",
  "password": "yourpassword123"
}
```

**Response (200):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": 1,
  "email": "john@example.com",
  "name": "John Doe"
}
```

**Errors:**
- `401 Unauthorized` - Invalid credentials

### GET /auth/me
Get current user information (protected route example).

**Headers:**
```
Authorization: Bearer <your-jwt-token>
```

**Response (200):**
```json
{
  "id": 1,
  "name": "John Doe",
  "email": "john@example.com"
}
```

**Errors:**
- `400 Bad Request` - Missing or invalid token
- `404 Not Found` - User not found

## Testing with curl

### Register a new user:
```bash
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "name": "John Doe",
    "email": "john@example.com",
    "password": "testpass123",
    "rgbHashColor": "#FF5733"
  }'
```

### Login:
```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "john@example.com",
    "password": "testpass123"
  }'
```

### Access protected route:
```bash
TOKEN="your-jwt-token-here"
curl -X GET http://localhost:8080/auth/me \
  -H "Authorization: Bearer $TOKEN"
```

## Implementation Details

### Components

1. **AuthModels.scala** - DTOs for authentication (LoginRequest, RegisterRequest, AuthResponse, JwtPayload)
2. **JwtService.scala** - JWT token generation and validation
3. **AuthService.scala** - Business logic for registration, login, and user management
4. **AuthRoutes.scala** - HTTP routes for authentication endpoints
5. **User.java** - Updated entity with `passwordHash` field

### Security Features

- Passwords are hashed using BCrypt (strength 10)
- JWT tokens are signed with HS256 algorithm
- Email validation
- Password minimum length (8 characters)
- Duplicate email prevention

### Database Schema

The `User` entity now includes a `passwordHash` field:
```java
@NotNull
@Size(min = 60, max = 60)
public String passwordHash;
```

### Dependencies Added

- `com.github.jwt-scala::jwt-zio-json` - JWT handling
- `org.springframework.security:spring-security-crypto` - BCrypt password hashing
- `org.bouncycastle:bcprov-jdk18on` - Cryptography provider

## Running the Application

```bash
# Set environment variables
export JWT_SECRET="your-secret-key"
export POSTGRES_USER="fingrid"
export POSTGRES_PASSWORD="yourpassword"
export DATABASE_URL="jdbc:postgresql://localhost:5432/fingrid"

# Run the service
mill fingrid-service.run
```

## Next Steps

To add JWT authentication to your other routes:

1. In your route handler, extract the Authorization header:
```scala
for
  authHeader <- ZIO.fromOption(req.header(Header.Authorization))
                  .orElseFail(new Exception("Missing auth header"))
  token      <- ZIO.succeed(authHeader.renderedValue.stripPrefix("Bearer ").trim)
  payload    <- ZIO.serviceWithZIO[JwtService](_.validateToken(token))
  // Now you have payload.userId and payload.email
  // Continue with your route logic...
yield response
```

2. Make sure your route requires `JwtService` in its environment:
```scala
val myRoutes: Routes[MyService & JwtService, Response] = Routes(...)
```

3. Ensure `JwtService` is provided in Main.scala layers (already done).
