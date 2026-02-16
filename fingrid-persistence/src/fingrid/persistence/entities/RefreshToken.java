package fingrid.persistence.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens", indexes = {
    @Index(name = "idx_refresh_token", columnList = "token"),
    @Index(name = "idx_user_id", columnList = "user_id"),
    @Index(name = "idx_expires_at", columnList = "expiresAt")
})
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @NotNull
    @Column(unique = true, length = 512)
    @Size(min = 32, max = 512)
    public String token;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    public User user;

    @NotNull
    @Column(nullable = false)
    public LocalDateTime issuedAt;

    @NotNull
    @Column(nullable = false)
    public LocalDateTime expiresAt;

    @Column(nullable = true)
    public LocalDateTime revokedAt;

    @Column(nullable = true, length = 512)
    public String replacedByToken;

    @Column(nullable = false)
    public boolean revoked = false;

    @NotNull
    @Column(nullable = false, length = 45)
    public String ipAddress;

    @Column(length = 255)
    public String userAgent;

    public RefreshToken() {
    }

    public RefreshToken(String token, User user, LocalDateTime issuedAt, LocalDateTime expiresAt, String ipAddress, String userAgent) {
        this.token = token;
        this.user = user;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isActive() {
        return !revoked && !isExpired();
    }

    public void revoke(String replacedBy) {
        this.revoked = true;
        this.revokedAt = LocalDateTime.now();
        this.replacedByToken = replacedBy;
    }
}
