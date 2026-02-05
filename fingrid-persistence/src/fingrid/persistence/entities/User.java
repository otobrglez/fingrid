package fingrid.persistence.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotNull
    public String name;

    @NotNull
    @Column(unique = true)
    public String email;

    @NotNull
    public String rgbHashColor;

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
}
