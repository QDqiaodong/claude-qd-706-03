package com.stone.mill.common;

/**
 * 并发冲突：两个人同时对同一块大板发起隔离、或同时复检同一条记录时，
 * 只有一个操作生效，失败方得到 409 + 可理解的提示 + 当前最新数据用于刷新。
 */
public class ConflictException extends RuntimeException {

    /** 冲突对象类型：hold 质量隔离记录 / delivery 发货单 / slab 大板 */
    private final String conflictType;

    /** 冲突资源 id（如已存在的未收口隔离记录 id），前端据此直接打开最新记录 */
    private final transient Object conflictData;

    public ConflictException(String message, String conflictType, Object conflictData) {
        super(message);
        this.conflictType = conflictType;
        this.conflictData = conflictData;
    }

    public String getConflictType() {
        return conflictType;
    }

    public Object getConflictData() {
        return conflictData;
    }
}
