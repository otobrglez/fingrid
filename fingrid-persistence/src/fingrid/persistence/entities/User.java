package fingrid.persistence.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    public UUID id;  // Keycloak subject ID - no auto-generation

    @NotNull
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100)
    public String username;

    @NotNull
    @Column(unique = true)
    @Email
    public String email;

    @NotNull
    public String rgbHashColor;

    @NotNull
    @Size(min = 60, max = 60)
    public String passwordHash;

    @OneToMany(mappedBy = "owner")
    public Set<Namespace> ownedNamespaces = new HashSet<>();

    @ManyToMany(mappedBy = "collaborators")
    public Set<Namespace> collaboratingNamespaces = new HashSet<>();

    @OneToMany(mappedBy = "creator")
    public Set<Transaction> createdTransactions = new HashSet<>();

    @ManyToMany(mappedBy = "users")
    public Set<Transaction> transactions = new HashSet<>();

    public User() {
    }

    public User(
            UUID id,
            String username,
            String email,
            String rgbHashColor,
            String passwordHash
    ) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.rgbHashColor = rgbHashColor;
        this.passwordHash = passwordHash;
    }
}
