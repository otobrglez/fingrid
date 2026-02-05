package fingrid.persistence.entities;

import jakarta.persistence.Tuple;
import org.hibernate.jpa.HibernatePersistenceConfiguration;

import static java.lang.System.out;

public class Main {
    static void main(String[] args) {
        out.println("Hello World! Args size = " + args.length);

        var sessionFactory = new HibernatePersistenceConfiguration("Bookshelf")
                .managedClasses(Author.class, Book.class)
                .jdbcUrl("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1")
                .jdbcCredentials("sa", "")
                .jdbcPoolSize(10)
                .showSql(true, !true, !true)
                .createEntityManagerFactory();

        sessionFactory.getSchemaManager().create(true);

        sessionFactory.inTransaction(session -> {
            var me = new Author("Oto Brglez");
            session.persist(me);
            var author = new Author("John Doe");
            session.persist(author);

            session.persist(new Book("The Great Gatsby", author));
            session.persist(new Book("The Lord of the Rings", author));
            session.persist(new Book("The Hobbit", author));

            for (int i = 0; i < 100; i++) {
                session.persist(new Book("Scala Book " + i, me));
            }
        });

        /*
        sessionFactory.inSession(session -> {
            var query = session.createQuery("select id, title from Book", Book.class);
            query.setMaxResults(10);
            query.getResultList().forEach(book -> out.println("[" + book.id + "] - " + book.title));
        });

         */
        sessionFactory.inSession(session -> {
            var builder = session.getCriteriaBuilder();
            // Create a query that returns a Tuple, not just a String
            var criteria = builder.createTupleQuery();
            var root = criteria.from(Book.class);

            // Use multiselect to fetch both the title and the author
            criteria.multiselect(
                    root.get(Book_.title),
                    root.get(Book_.author)
            );

            var query = session.createQuery(criteria);
            query.setMaxResults(10);
            query.getResultList().forEach(tuple -> {
                var title = tuple.get(0, String.class);
                var author = tuple.get(1, Author.class);
                out.println("Book: " + title + ", Author: " + author.name);
            });
        });
    }
}

