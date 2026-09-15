package io.aligo.travel.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;
import java.util.Random;

/**
 * 行程卡片工具：把机票 / 酒店 / 天气要素组装为一张结构化的"一键下单"卡片。
 *
 * <p>对应文章"出差事项收集 → 行程规划 → 一键下单"业务闭环中的卡片生成步骤。
 * 输出层会把它作为 card 事件推给前端（映射关系见 TaskCollector 的注释）。
 */
@Component
public class ItineraryCardTool {

    @Tool(description = "生成行程卡片：汇总去程机票、酒店、天气，提供一键下单入口。")
    public String build_itinerary_card(
            @ToolParam(name = "departure", description = "出发城市") String departure,
            @ToolParam(name = "destination", description = "到达城市") String destination,
            @ToolParam(name = "date", description = "日期 yyyy-MM-dd") String date,
            @ToolParam(name = "flightNo", description = "选定航班号，空则推荐第一班") String flightNo,
            @ToolParam(name = "hotelName", description = "选定酒店名，空则推荐第一家") String hotelName) {
        int seed = (departure + destination).hashCode();
        String recFlight = "MU" + (1000 + Math.abs(seed) % 9000);
        String recHotel = destination + "全季酒店";
        int flightPrice = 380 + Math.abs(seed) % 420;
        int hotelPrice = 260 + Math.abs(seed + 41) % 380;
        String orderUrl = "https://aligo.example.com/order/" + Math.abs(seed % 100000);
        return String.format(
                "{\"_card\":\"itinerary\",\"departure\":\"%s\",\"destination\":\"%s\",\"date\":\"%s\","
                        + "\"flightNo\":\"%s\",\"flightPrice\":%d,\"hotelName\":\"%s\",\"hotelPrice\":%d,"
                        + "\"totalEstimate\":%d,\"orderUrl\":\"%s\"}",
                departure, destination, date,
                flightNo.isBlank() ? recFlight : flightNo, flightPrice,
                hotelName.isBlank() ? recHotel : hotelName, hotelPrice,
                flightPrice + hotelPrice, orderUrl);
    }
}
