-- ============================================================
-- AliGo 智能差旅助手 · 数据库 schema
-- MySQL 5.7+ ：审批单主表 + 审计表 + 触发器 + 存储过程 + 函数
-- ============================================================
CREATE DATABASE IF NOT EXISTS aligo_travel DEFAULT CHARACTER SET utf8mb4;
USE aligo_travel;

DROP TABLE IF EXISTS approval_audit;
DROP TABLE IF EXISTS travel_approval;

-- 出差审批单
CREATE TABLE travel_approval (
  apply_id     VARCHAR(32)  NOT NULL COMMENT '申请单号 OA-xxxxx',
  applicant    VARCHAR(64)  DEFAULT NULL COMMENT '申请人',
  destination  VARCHAR(64)  NOT NULL COMMENT '出差目的地',
  travel_date  DATE         DEFAULT NULL COMMENT '出差日期',
  budget       VARCHAR(32)  DEFAULT NULL COMMENT '预算',
  reason       VARCHAR(512) DEFAULT NULL COMMENT '事由',
  status       VARCHAR(64)  DEFAULT '审批中（直属上级）' COMMENT '审批状态',
  sla          VARCHAR(32)  DEFAULT NULL COMMENT '时效承诺',
  apply_time   DATE         DEFAULT NULL COMMENT '申请日期',
  created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (apply_id),
  KEY idx_dest (destination),
  KEY idx_status (status),
  KEY idx_apply_time (apply_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出差审批单';

-- 审批审计表（由触发器自动写入，应用层不直接写）
CREATE TABLE approval_audit (
  audit_id   BIGINT       NOT NULL AUTO_INCREMENT,
  apply_id   VARCHAR(32)  NOT NULL,
  action     VARCHAR(16)  NOT NULL COMMENT 'INSERT / UPDATE',
  old_status VARCHAR(64)  DEFAULT NULL,
  new_status VARCHAR(64)  DEFAULT NULL,
  changed_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (audit_id),
  KEY idx_apply (apply_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批变更审计';

DELIMITER $$

-- 触发器①：新增审批单 → 写审计
DROP TRIGGER IF EXISTS trg_approval_after_insert $$
CREATE TRIGGER trg_approval_after_insert AFTER INSERT ON travel_approval
FOR EACH ROW
BEGIN
  INSERT INTO approval_audit (apply_id, action, old_status, new_status)
  VALUES (NEW.apply_id, 'INSERT', NULL, NEW.status);
END $$

-- 触发器②：状态变更 → 写审计（状态没变则不记）
DROP TRIGGER IF EXISTS trg_approval_after_update $$
CREATE TRIGGER trg_approval_after_update AFTER UPDATE ON travel_approval
FOR EACH ROW
BEGIN
  IF NEW.status <> OLD.status OR (NEW.status IS NULL AND OLD.status IS NOT NULL) THEN
    INSERT INTO approval_audit (apply_id, action, old_status, new_status)
    VALUES (NEW.apply_id, 'UPDATE', OLD.status, NEW.status);
  END IF;
END $$

-- 存储过程：按月统计出差申请
DROP PROCEDURE IF EXISTS sp_approval_stats_by_month $$
CREATE PROCEDURE sp_approval_stats_by_month (IN p_year INT, IN p_month INT)
BEGIN
  SELECT COUNT(*)                                                   AS total,
         SUM(CASE WHEN status LIKE '审批中%' THEN 1 ELSE 0 END)      AS pending,
         SUM(CASE WHEN status = '已通过'   THEN 1 ELSE 0 END)        AS approved,
         SUM(CASE WHEN status LIKE '已驳回%' THEN 1 ELSE 0 END)      AS rejected
  FROM travel_approval
  WHERE YEAR(apply_time) = p_year AND MONTH(apply_time) = p_month;
END $$

-- 存储过程：把某张单子审批通过（演示"写操作走存储过程"）
DROP PROCEDURE IF EXISTS sp_approve_application $$
CREATE PROCEDURE sp_approve_application (IN p_apply_id VARCHAR(32), IN p_status VARCHAR(64))
BEGIN
  UPDATE travel_approval SET status = p_status WHERE apply_id = p_apply_id;
END $$

-- 函数：统计某目的地累计申请数
DROP FUNCTION IF EXISTS fn_count_by_destination $$
CREATE FUNCTION fn_count_by_destination (p_dest VARCHAR(64)) RETURNS INT READS SQL DATA
BEGIN
  DECLARE cnt INT DEFAULT 0;
  SELECT COUNT(*) INTO cnt FROM travel_approval WHERE destination = p_dest;
  RETURN cnt;
END $$

DELIMITER ;
