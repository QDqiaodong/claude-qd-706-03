package com.stone.mill.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.stone.mill.entity.QualityHold;
import com.stone.mill.repository.QualityHoldRepository;

/**
 * 独立事务桥：当外层事务因唯一键冲突被标记 rollback-only 后，
 * 仍需在一个【全新事务】里把冲突方（别人刚建好的未收口记录）读出来返回给失败方，
 * 因此这些读取必须挂 REQUIRES_NEW。
 */
@Component
public class QualityHoldTxBridge {

    private final QualityHoldRepository holdRepository;

    public QualityHoldTxBridge(QualityHoldRepository holdRepository) {
        this.holdRepository = holdRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public QualityHold findActive(Long slabId) {
        return holdRepository.findActiveBySlab(slabId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public QualityHold findById(Long id) {
        return holdRepository.findById(id).orElse(null);
    }
}
