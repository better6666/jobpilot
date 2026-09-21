package com.jobpilot.delivery;

/** 投递一个岗位的结果，由适配器返回、编排层落库 */
public record DeliveryOutcome(DeliveryStatus status, String failReason, String greeting) {

    public static DeliveryOutcome delivered(String greeting) {
        return new DeliveryOutcome(DeliveryStatus.DELIVERED, null, greeting);
    }

    public static DeliveryOutcome preview(String greeting) {
        return new DeliveryOutcome(DeliveryStatus.PREVIEW, null, greeting);
    }

    public static DeliveryOutcome failed(String reason) {
        return new DeliveryOutcome(DeliveryStatus.FAILED, reason, null);
    }

    public static DeliveryOutcome limit(String reason) {
        return new DeliveryOutcome(DeliveryStatus.LIMIT, reason, null);
    }
}
