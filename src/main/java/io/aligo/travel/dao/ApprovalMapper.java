package io.aligo.travel.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 出差审批单的数据访问层（MyBatis）。 */
@Mapper
public interface ApprovalMapper {

    /** 新增申请单；状态变更由数据库触发器自动写审计表。 */
    int insert(Approval approval);

    /** 全部申请单（按单号倒序）。 */
    List<Approval> findAll();

    Approval findById(@Param("applyId") String applyId);

    /** 审批状态流转（走存储过程 sp_approve_application）。 */
    void approveByProcedure(@Param("applyId") String applyId, @Param("status") String status);

    /** 生成下一个申请单号（SQL 内计算，避免应用层维护自增状态）。 */
    String nextApplyId();

    /** 按月统计（走存储过程 sp_approval_stats_by_month）。 */
    Map<String, Object> statsByMonth(@Param("year") int year, @Param("month") int month);

    /** 某目的地累计申请数（走数据库函数 fn_count_by_destination）。 */
    Integer countByDestination(@Param("destination") String destination);
}
