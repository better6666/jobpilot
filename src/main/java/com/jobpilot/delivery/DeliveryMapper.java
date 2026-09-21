package com.jobpilot.delivery;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** deliveries 表的 mapper，四个平台共用 */
@Mapper
public interface DeliveryMapper extends BaseMapper<Delivery> {
}
