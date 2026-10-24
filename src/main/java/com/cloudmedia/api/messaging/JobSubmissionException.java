package com.cloudmedia.api.messaging;

public class JobSubmissionException extends RuntimeException {
    private final String jobId;
    public JobSubmissionException(String jobId, Throwable cause) {
        super("Không thể xác nhận tác vụ với RabbitMQ; kiểm tra trạng thái trước khi gửi lại", cause);
        this.jobId = jobId;
    }
    public String getJobId() { return jobId; }
}
