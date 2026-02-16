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
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

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
    private Set<Namespace> ownedNamespaces = new HashSet<>();

    @ManyToMany(mappedBy = "collaborators")
    private Set<Namespace> collaboratingNamespaces = new HashSet<>();

    @OneToMany(mappedBy = "creator")
    private Set<Transaction> createdTransactions = new HashSet<>();

    @ManyToMany(mappedBy = "users")
    private Set<Transaction> transactions = new HashSet<>();

    public User() {
    }

    public User(
            String username,
            String email,
            String rgbHashColor,
            String passwordHash
    ) {
        this.username = username;
        this.email = email;
        this.rgbHashColor = rgbHashColor;
        this.passwordHash = passwordHash;
    }
}
