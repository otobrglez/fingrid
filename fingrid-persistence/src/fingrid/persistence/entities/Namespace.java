package fingrid.persistence.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.SQLDelete;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "namespaces")
@FilterDef(name = "deletedNamespaceFilter")
@Filter(name = "deletedNamespaceFilter", condition = "deleted_at IS NULL")
@SQLDelete(sql = "UPDATE namespaces SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
public class Namespace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @NotNull
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100)
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

    @Column(name = "deleted_at")
    public Instant deletedAt;

    public Namespace() {
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Namespace(String name, User owner) {
        this.name = name;
        this.owner = owner;
    }
}
