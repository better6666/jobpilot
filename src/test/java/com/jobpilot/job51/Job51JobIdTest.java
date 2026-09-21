package com.jobpilot.job51;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Job51Driver 里两个纯解析方法的单测。
 * <p>
 * 这两个方法是修 bug 时抽出来的：51job 的数字 jobId 不在链接里，
 * 而在卡片 div 的 {@code sensorsdata} 埋点 JSON 里，所以解析逻辑必须
 * 能在不启动浏览器的情况下验证——线上 DOM 一改，这里先红。
 */
@DisplayName("51job jobId / sensorsdata 解析")
class Job51JobIdTest {

    /** 真机抓到的 sensorsdata 原文（HTML 实体编码形态） */
    private static final String REAL_SENSORS =
            "{&quot;jobId&quot;:&quot;173701162&quot;,&quot;jobTitle&quot;:&quot;市场岗-苏州城市公司-万新生-27届&quot;"
                    + ",&quot;jobType&quot;:&quot;0&quot;,&quot;funcType&quot;:&quot;0304&quot;,&quot;jobSalary&quot;:&quot;7-8千&quot;"
                    + ",&quot;jobArea&quot;:&quot;苏州&quot;,&quot;jobYear&quot;:&quot;无需经验&quot;,&quot;jobDegree&quot;:&quot;本科&quot;"
                    + ",&quot;companyId&quot;:&quot;10192136&quot;,&quot;jobTime&quot;:&quot;2026-09-18 14:08:34&quot;"
                    + ",&quot;isPromote&quot;:&quot;否&quot;,&quot;advId&quot;:&quot;&quot;,&quot;jobLabel&quot;:&quot;&quot;"
                    + ",&quot;pageCode&quot;:&quot;sou|sou|soulb&quot;,&quot;requestId&quot;:&quot;31a828140ab348fa44f37b7a57e72f64&quot;"
                    + ",&quot;searchType&quot;:2,&quot;keyword&quot;:&quot;陈列设计&quot;,&quot;pageNum&quot;:3,&quot;jobRank&quot;:0"
                    + ",&quot;exrInfo&quot;:&quot;{\\&quot;isHrLabel\\&quot;:\\&quot;否\\&quot;}&quot;}";

    @Nested
    @DisplayName("jobIdFrom")
    class JobIdFrom {

        @Test
        @DisplayName("真机 sensorsdata 提出 9 位 jobId")
        void realSensorsData() {
            assertThat(Job51Driver.jobIdFrom(REAL_SENSORS)).isEqualTo("173701162");
        }

        @Test
        @DisplayName("已是普通 JSON 的 sensorsdata 同样能提")
        void plainJson() {
            assertThat(Job51Driver.jobIdFrom("{\"jobId\":\"166990732\",\"jobTitle\":\"香氛设计师\"}"))
                    .isEqualTo("166990732");
        }

        @Test
        @DisplayName("jobId 是数字而非字符串时也能提")
        void numericValue() {
            assertThat(Job51Driver.jobIdFrom("{\"jobId\":173701162}")).isEqualTo("173701162");
        }

        @Test
        @DisplayName("referJobId 空值不干扰 jobId 提取")
        void referJobIdDoesNotShadow() {
            // exrInfo 里嵌套的 referJobId 是空串，且键名大小写不同，不该被当成 jobId
            String s = "{\"jobId\":\"123456789\",\"exrInfo\":\"{\\\"referJobId\\\":\\\"\\\"}\"}";
            assertThat(Job51Driver.jobIdFrom(s)).isEqualTo("123456789");
        }

        @Test
        @DisplayName("老格式详情页链接：/suzhou/166990732.html")
        void legacyDetailPath() {
            assertThat(Job51Driver.jobIdFrom("https://jobs.51job.com/suzhou/166990732.html?s=sou&t=0_0"))
                    .isEqualTo("166990732");
        }

        @Test
        @DisplayName("查询串形式的 jobId")
        void queryParam() {
            assertThat(Job51Driver.jobIdFrom("https://we.51job.com/pc/jobdetail?jobId=166990732"))
                    .isEqualTo("166990732");
        }

        @Test
        @DisplayName("URL 编码形式的 jobId%3D")
        void urlEncodedParam() {
            assertThat(Job51Driver.jobIdFrom("/search?jobId%3D166990732&x=1")).isEqualTo("166990732");
        }

        @Test
        @DisplayName("公司页链接 /all/coXXXX.html 提不出 jobId")
        void companyLinkHasNoJobId() {
            assertThat(Job51Driver.jobIdFrom("https://jobs.51job.com/all/coUjUHZ1Y2DjEBZABh.html")).isNull();
        }

        @Test
        @DisplayName("空值 / null 一律返回 null")
        void blanks() {
            assertThat(Job51Driver.jobIdFrom(null)).isNull();
            assertThat(Job51Driver.jobIdFrom("")).isNull();
            assertThat(Job51Driver.jobIdFrom("   ")).isNull();
            assertThat(Job51Driver.jobIdFrom("null")).isNull();
        }

        @Test
        @DisplayName("没有任何 jobId 线索时返回 null")
        void noJobIdAnywhere() {
            assertThat(Job51Driver.jobIdFrom("{\"jobTitle\":\"平面设计助理\"}")).isNull();
        }
    }

    @Nested
    @DisplayName("fillFromSensorsData")
    class Fill {

        @Test
        @DisplayName("真机 sensorsdata 填出全部字段")
        void realSensorsData() {
            Job51JobCard card = new Job51JobCard();
            Job51Driver.fillFromSensorsData(card, REAL_SENSORS);

            assertThat(card.getJobName()).isEqualTo("市场岗-苏州城市公司-万新生-27届");
            assertThat(card.getSalaryDesc()).isEqualTo("7-8千");
            assertThat(card.getLocationRaw()).isEqualTo("苏州");
            assertThat(card.getCityName()).isEqualTo("苏州");
            assertThat(card.getAreaDistrict()).isNull();
            assertThat(card.getJobExperience()).isEqualTo("无需经验");
            assertThat(card.getJobDegree()).isEqualTo("本科");
        }

        @Test
        @DisplayName("带区县的地区串拆成城市 + 区")
        void areaSplit() {
            Job51JobCard card = new Job51JobCard();
            Job51Driver.fillFromSensorsData(card,
                    "{\"jobId\":\"1\",\"jobTitle\":\"x\",\"jobArea\":\"苏州-姑苏区\"}");

            assertThat(card.getCityName()).isEqualTo("苏州");
            assertThat(card.getAreaDistrict()).isEqualTo("姑苏区");
        }

        @Test
        @DisplayName("空字符串值按缺失处理，不覆盖已有值")
        void emptyValueIsMissing() {
            Job51JobCard card = new Job51JobCard();
            card.setJobName("已有标题");
            Job51Driver.fillFromSensorsData(card, "{\"jobTitle\":\"\",\"jobSalary\":\"6-9千\"}");

            assertThat(card.getJobName()).isEqualTo("已有标题");
            assertThat(card.getSalaryDesc()).isEqualTo("6-9千");
        }

        @Test
        @DisplayName("键名缺失时不报错，其余字段照填")
        void missingKeys() {
            Job51JobCard card = new Job51JobCard();
            Job51Driver.fillFromSensorsData(card, "{\"jobId\":\"1\",\"jobTitle\":\"陈列设计助理\"}");

            assertThat(card.getJobName()).isEqualTo("陈列设计助理");
            assertThat(card.getSalaryDesc()).isNull();
            assertThat(card.getLocationRaw()).isNull();
            assertThat(card.getCityName()).isNull();
            assertThat(card.getJobExperience()).isNull();
            assertThat(card.getJobDegree()).isNull();
        }

        @Test
        @DisplayName("null / 空 sensors 不炸，卡片保持原样")
        void nullSafe() {
            Job51JobCard card = new Job51JobCard();
            card.setJobName("原值");
            Job51Driver.fillFromSensorsData(card, null);
            Job51Driver.fillFromSensorsData(card, "");
            Job51Driver.fillFromSensorsData(card, "   ");
            Job51Driver.fillFromSensorsData(null, "{\"jobTitle\":\"x\"}");

            assertThat(card.getJobName()).isEqualTo("原值");
        }
    }
}
