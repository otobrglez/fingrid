package fingrid.persistence.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "namespaces")
public class Namespace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotNull
    public String name;

    @ManyToOne
    @JoinColumn(name = "owner_id")
    @NotNull
    private User owner;

    @ManyToMany
    @JoinTable(
        name = "namespace_collaborators",
        joinColumns = @JoinColumn(name = "namespace_id"),
        inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    private Set<User> collaborators = new HashSet<>();

    @OneToMany(mappedBy = "namespace")
    private Set<Category> categories = new HashSet<>();

    public boolean deleted = false;

    public Namespace() {
    }

    public Namespace(String name, User owner) {
        this.name = name;
        this.owner = owner;
    }
}
