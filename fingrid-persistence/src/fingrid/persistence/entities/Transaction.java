package fingrid.persistence.entities;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotNull
    @Min(0)
    @Max(1000000)
    public BigDecimal amount;

    @NotNull
    @Enumerated(EnumType.STRING)
    public Currency currency;

    @NotNull
    @Enumerated(EnumType.STRING)
    public TransactionKind kind;

    @NotNull
    public LocalDateTime datetime;

    @NotNull
    @Column(name = "year_month")
    @Convert(converter = YearMonthConverter.class)
    public YearMonth yearMonth;

    @ManyToOne
    @JoinColumn(name = "category_id")
    @NotNull
    private Category category;

    @ManyToOne
    @JoinColumn(name = "creator_id")
    @NotNull
    private User creator;

    @ManyToMany
    @JoinTable(
        name = "transaction_users",
        joinColumns = @JoinColumn(name = "transaction_id"),
        inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    private Set<User> users = new HashSet<>();

    public boolean deleted = false;

    public Transaction() {
    }

    public Transaction(BigDecimal amount, Currency currency, TransactionKind kind,
                      LocalDateTime datetime, YearMonth yearMonth, Category category, User creator) {
        this.amount = amount;
        this.currency = currency;
        this.kind = kind;
        this.datetime = datetime;
        this.yearMonth = yearMonth;
        this.category = category;
        this.creator = creator;
    }

    public Set<User> getUsers() {
        return users;
    }
}
