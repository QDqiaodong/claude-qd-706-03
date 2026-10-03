package com.stone.mill.repository;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 单据号序列：靠主键行的行锁原子自增取号，两个并发事务拿到的序号必然不同，
 * 避免「先 count 后插入」在并发下产生同号。
 *
 * <p>用 MySQL 的 {@code LAST_INSERT_ID(expr)}：UPDATE 在当前连接上原子自增并保存结果，
 * 紧接着 {@code SELECT LAST_INSERT_ID()} 取号；同事务同一连接，取号与业务写入一致。
 */
@Repository
public class SequenceRepository {

    @PersistenceContext
    private EntityManager em;

    /** 取下一个序号：键不存在先补 0，再原子 +1 返回（并发事务在行锁上串行，绝不重号）。 */
    public long nextValue(String key) {
        em.createNativeQuery("INSERT IGNORE INTO mill_seq (seq_key, seq_value) VALUES (:k, 0)")
                .setParameter("k", key)
                .executeUpdate();
        em.createNativeQuery("UPDATE mill_seq SET seq_value = LAST_INSERT_ID(seq_value + 1) WHERE seq_key = :k")
                .setParameter("k", key)
                .executeUpdate();
        // MySQL 驱动返回 BigInteger、MariaDB 驱动返回 Long，统一按 Number 取
        Number value = (Number) em.createNativeQuery("SELECT LAST_INSERT_ID()")
                .getSingleResult();
        return value.longValue();
    }
}
