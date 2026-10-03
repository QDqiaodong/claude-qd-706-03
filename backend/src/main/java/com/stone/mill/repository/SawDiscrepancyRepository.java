package com.stone.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.stone.mill.entity.SawDiscrepancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

/** 模块十一：计划实产差异仓储（交接时整批重建 OPEN 差异；核清后只改状态，不删历史）。 */
@Repository
public class SawDiscrepancyRepository {

    @PersistenceContext
    private EntityManager em;

    public Optional<SawDiscrepancy> findById(Long id) {
        return Optional.ofNullable(em.find(SawDiscrepancy.class, id));
    }

    public void save(SawDiscrepancy diff) {
        if (diff.getId() == null) {
            em.persist(diff);
        } else {
            em.merge(diff);
        }
    }

    public void delete(SawDiscrepancy diff) {
        em.remove(em.contains(diff) ? diff : em.merge(diff));
    }

    public List<SawDiscrepancy> listByBatch(Long batchId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawDiscrepancy> cq = cb.createQuery(SawDiscrepancy.class);
        Root<SawDiscrepancy> root = cq.from(SawDiscrepancy.class);
        cq.select(root).where(cb.equal(root.get("batchId"), batchId))
                .orderBy(cb.asc(root.get("createdAt")), cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }

    public List<SawDiscrepancy> listOpenByBatch(Long batchId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawDiscrepancy> cq = cb.createQuery(SawDiscrepancy.class);
        Root<SawDiscrepancy> root = cq.from(SawDiscrepancy.class);
        cq.select(root).where(cb.equal(root.get("batchId"), batchId),
                cb.equal(root.get("status"), "OPEN"))
                .orderBy(cb.asc(root.get("createdAt")), cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }

    public long countOpenByBatch(Long batchId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<SawDiscrepancy> root = cq.from(SawDiscrepancy.class);
        cq.select(cb.count(root)).where(cb.equal(root.get("batchId"), batchId),
                cb.equal(root.get("status"), "OPEN"));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    /** 一批批次下的未核清差异条数（工单详情的「差异阻断」计数）。 */
    public long countOpenByBatches(List<Long> batchIds) {
        if (batchIds == null || batchIds.isEmpty()) {
            return 0L;
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<SawDiscrepancy> root = cq.from(SawDiscrepancy.class);
        cq.select(cb.count(root)).where(root.get("batchId").in(batchIds),
                cb.equal(root.get("status"), "OPEN"));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }
}
