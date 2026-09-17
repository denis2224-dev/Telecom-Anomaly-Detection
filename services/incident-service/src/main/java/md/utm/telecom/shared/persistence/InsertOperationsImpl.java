package md.utm.telecom.shared.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

public class InsertOperationsImpl<T> implements InsertOperations<T> {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public T insert(T entity) {
        entityManager.persist(entity);
        return entity;
    }
}
