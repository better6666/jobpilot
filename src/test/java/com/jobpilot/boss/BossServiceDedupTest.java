package com.jobpilot.boss;

import com.jobpilot.browser.BrowserManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 去重与落库路径：预演不能挡住后续真实投递，已投递必须挡住。
 * deliver 被 mock 掉，不碰浏览器。
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = Strictness.LENIENT)
class BossServiceDedupTest {

    @Mock
    private BossProperties properties;

    @Mock
    private BossDriver driver;

    @Mock
    private BossDeliveryMapper deliveryMapper;

    @Mock
    private BrowserManager browserManager;

    private BossService service;

    @BeforeEach
    void setUp() {
        BossProperties.BossConfig config = new BossProperties.BossConfig();
        config.setSayHi("您好");
        config.setDryRun(true);
        when(properties.get()).thenReturn(config);
        service = new BossService(properties, driver, deliveryMapper, browserManager, new BossOptions(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    private BossJobCard card() {
        BossJobCard card = new BossJobCard();
        card.setEncryptId("enc-1");
        card.setEncryptUserId("boss-1");
        card.setJobName("陈列设计");
        card.setBrandName("某品牌");
        return card;
    }

    private BossDelivery existing(String status) {
        BossDelivery delivery = new BossDelivery();
        delivery.setId(1L);
        delivery.setDeliveryStatus(status);
        return delivery;
    }

    @Test
    void 已投递的岗位直接跳过不再碰浏览器() {
        when(deliveryMapper.selectOne(any())).thenReturn(existing("已投递"));

        service.processCard(card(), "陈列设计", properties.get(), null);

        verify(driver, never()).deliver(any(), any(), anyString(), anyBoolean(), any(), any());
        verify(deliveryMapper, never()).insert(any(BossDelivery.class));
        verify(deliveryMapper, never()).updateById(any(BossDelivery.class));
        assertEquals(1, service.status().getSkipped());
    }

    @Test
    void 预演过的岗位真实投递时不被挡住() {
        when(deliveryMapper.selectOne(any())).thenReturn(existing("预演"));
        when(driver.deliver(any(), any(), anyString(), anyBoolean(), any(), any()))
                .thenReturn(new BossDriver.DeliveryOutcome(BossDriver.DeliveryStatus.DELIVERED, null, "您好"));

        service.processCard(card(), "陈列设计", properties.get(), null);

        verify(driver).deliver(any(), any(), anyString(), anyBoolean(), any(), any());
        verify(deliveryMapper).updateById(any(BossDelivery.class));
        verify(deliveryMapper, never()).insert(any(BossDelivery.class));
        assertEquals(1, service.status().getDelivered());
        assertEquals(0, service.status().getSkipped());
    }

    @Test
    void 新岗位投递成功后insert且带上打招呼语() {
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(driver.deliver(any(), any(), anyString(), anyBoolean(), any(), any()))
                .thenReturn(new BossDriver.DeliveryOutcome(BossDriver.DeliveryStatus.DELIVERED, null, "您好"));

        service.processCard(card(), "陈列设计", properties.get(), null);

        ArgumentCaptor<BossDelivery> captor = ArgumentCaptor.forClass(BossDelivery.class);
        verify(deliveryMapper).insert(captor.capture());
        BossDelivery saved = captor.getValue();
        assertEquals("已投递", saved.getDeliveryStatus());
        assertEquals("您好", saved.getGreeting());
        assertEquals("某品牌", saved.getBrandName());
        assertEquals("陈列设计", saved.getKeyword());
    }

    @Test
    void 投递失败允许重试并记录原因() {
        when(deliveryMapper.selectOne(any())).thenReturn(existing("投递失败"));
        when(driver.deliver(any(), any(), anyString(), anyBoolean(), any(), any()))
                .thenReturn(new BossDriver.DeliveryOutcome(BossDriver.DeliveryStatus.FAILED, "未找到聊天输入框", "您好"));

        service.processCard(card(), "陈列设计", properties.get(), null);

        verify(deliveryMapper).updateById(any(BossDelivery.class));
        assertEquals(1, service.status().getFailed());
        assertEquals(0, service.status().getSkipped());
    }
}
