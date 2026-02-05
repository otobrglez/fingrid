package fingrid.persistence.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotNull
    public String name;

    @ManyToOne
    @JoinColumn(name = "namespace_id")
    @NotNull
    private Namespace namespace;

    @OneToMany(mappedBy = "category")
    private Set<Transaction> transactions = new HashSet<>();

    public boolean deleted = false;

    public Category() {
    }

    public Category(String name, Namespace namespace) {
        this.name = name;
        this.namespace = namespace;
    }
}
