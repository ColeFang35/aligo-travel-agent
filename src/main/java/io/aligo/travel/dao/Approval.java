package io.aligo.travel.dao;

import java.time.LocalDate;

/** 出差审批单（对应 travel_approval 表）。 */
public class Approval {
    private String applyId;
    private String applicant;
    private String destination;
    private LocalDate travelDate;
    private String budget;
    private String reason;
    private String status;
    private String sla;
    private LocalDate applyTime;

    public String getApplyId() { return applyId; }
    public void setApplyId(String applyId) { this.applyId = applyId; }
    public String getApplicant() { return applicant; }
    public void setApplicant(String applicant) { this.applicant = applicant; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public LocalDate getTravelDate() { return travelDate; }
    public void setTravelDate(LocalDate travelDate) { this.travelDate = travelDate; }
    public String getBudget() { return budget; }
    public void setBudget(String budget) { this.budget = budget; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSla() { return sla; }
    public void setSla(String sla) { this.sla = sla; }
    public LocalDate getApplyTime() { return applyTime; }
    public void setApplyTime(LocalDate applyTime) { this.applyTime = applyTime; }
}
