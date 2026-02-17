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
@Table(name = "categories")
@FilterDef(name = "deletedCategoryFilter")
@Filter(name = "deletedCategoryFilter", condition = "deleted_at IS NULL")
@SQLDelete(sql = "UPDATE categories SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @NotNull
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100)
    public String name;

    @ManyToOne
    @JoinColumn(name = "namespace_id")
    @NotNull
    public Namespace namespace;

    @OneToMany(mappedBy = "category")
    public Set<Transaction> transactions = new HashSet<>();

    @Column(name = "deleted_at")
    public Instant deletedAt;

    public Category() {
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Category(String name, Namespace namespace) {
        this.name = name;
        this.namespace = namespace;
    }
}
