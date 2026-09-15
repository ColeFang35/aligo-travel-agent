package io.aligo.travel.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 商旅信息查询工具：机票 / 酒店 / 天气 / 时间转化。
 *
 * <p>结果 JSON 内置 {@code _card} 标记，流式输出层在 tool_result 事件中直接
 * 抽成前端卡片渲染（对应文章"维护卡片数据与任务的映射关系"的输出层职责）。
 * 数据为基于城市哈希的确定性伪数据，保证演示可复现。
 */
@Component
public class TravelTools {

    @Tool(description = "查询机票。返回候选航班列表（航班号/时刻/价格），供用户点选。")
    public String flight_search(
            @ToolParam(name = "departure", description = "出发城市，如杭州") String departure,
            @ToolParam(name = "destination", description = "到达城市，如上海") String destination,
            @ToolParam(name = "date", description = "出发日期，yyyy-MM-dd") String date) {
        int seed = (departure + destination).hashCode();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"_card\":\"flight\",\"route\":\"").append(departure).append("→").append(destination)
                .append("\",\"date\":\"").append(date).append("\",\"flights\":[");
        String[] carriers = {"MU", "CA", "CZ", "HO", "GJ"};
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                sb.append(',');
            }
            int price = 380 + Math.abs(seed + i * 37) % 420;
            int hour = 7 + (Math.abs(seed + i * 11) % 13);
            String no = carriers[Math.abs(seed + i) % carriers.length] + (1000 + Math.abs(seed + i * 53) % 9000);
            sb.append("{\"flightNo\":\"").append(no).append("\",\"dep\":\"").append(String.format("%02d:%02d", hour, (i * 25) % 60))
                    .append("\",\"arr\":\"").append(String.format("%02d:%02d", hour + 2, (i * 25) % 60))
                    .append("\",\"price\":").append(price).append(",\"cabin\":\"经济舱\"}");
        }
        return sb.append("]}").toString();
    }

    @Tool(description = "查询酒店。返回候选酒店（名称/星级/价格/差标是否符合），供用户点选。")
    public String hotel_search(
            @ToolParam(name = "city", description = "城市，如上海") String city,
            @ToolParam(name = "checkInDate", description = "入住日期，yyyy-MM-dd") String checkInDate,
            @ToolParam(name = "nights", description = "间夜数") int nights) {
        int seed = city.hashCode();
        String[] names = {"全季酒店", "如家商旅", "亚朵酒店", "汉庭优佳", "桔子水晶"};
        StringBuilder sb = new StringBuilder();
        sb.append("{\"_card\":\"hotel\",\"city\":\"").append(city).append("\",\"checkInDate\":\"").append(checkInDate)
                .append("\",\"nights\":").append(nights).append(",\"hotels\":[");
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                sb.append(',');
            }
            int price = 260 + Math.abs(seed + i * 41) % 380;
            int stars = 3 + Math.abs(seed + i) % 3;
            sb.append("{\"name\":\"").append(city).append(names[Math.abs(seed + i) % names.length])
                    .append("\",\"stars\":").append(stars).append(",\"price\":").append(price)
                    .append(",\"withinPolicy\":").append(price <= 500).append("}");
        }
        return sb.append("]}").toString();
    }

    @Tool(description = "查询天气。返回目的地天气概况，供行程体验评估。")
    public String weather_query(
            @ToolParam(name = "city", description = "城市，如上海") String city) {
        int seed = city.hashCode();
        String[] w = {"晴", "多云", "小雨", "阴", "雷阵雨"};
        String cond = w[Math.abs(seed) % w.length];
        int high = 18 + Math.abs(seed) % 15;
        int low = high - 7;
        return String.format("{\"_card\":\"weather\",\"city\":\"%s\",\"condition\":\"%s\",\"high\":%d,\"low\":%d,\"tip\":\"出行注意温差\"}",
                city, cond, high, low);
    }

    @Tool(description = "日期时间转化：把口语化时间表达转成具体日期。")
    public String parse_time(
            @ToolParam(name = "expression", description = "口语时间表达，如'后天上午'") String expression) {
        java.time.LocalDate d = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        java.time.LocalDate t = d;
        int offset = 0;
        if (expression.contains("大后天")) {
            offset = 3;
        } else if (expression.contains("后天")) {
            offset = 2;
        } else if (expression.contains("明天")) {
            offset = 1;
        }
        t = d.plusDays(offset);
        String slot = expression.contains("上午") ? "上午" : expression.contains("下午") ? "下午" : "全天";
        return String.format("{\"_card\":\"time\",\"expression\":\"%s\",\"parsedDate\":\"%s\",\"slot\":\"%s\",\"weekday\":\"%s\"}",
                expression, t, slot, weekday(t));
    }

    private String weekday(java.time.LocalDate d) {
        return "周" + "一二三四五六日".charAt(d.getDayOfWeek().getValue() - 1);
    }
}
