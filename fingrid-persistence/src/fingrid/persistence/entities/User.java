package fingrid.persistence.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotNull
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100)
    public String name;

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

    public User(String name, String email, String rgbHashColor) {
        this.name = name;
        this.email = email;
        this.rgbHashColor = rgbHashColor;
    }

    public User(String name, String email, String rgbHashColor, String passwordHash) {
        this.name = name;
        this.email = email;
        this.rgbHashColor = rgbHashColor;
        this.passwordHash = passwordHash;
    }
}
