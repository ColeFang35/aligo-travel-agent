package io.aligo.travel.context;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 槽位抽取：从用户 query 中提取出/到达城市与日期。
 *
 * <p>对应文章"商旅应用场景：意图识别、日期时间转化"。工程化规则抽取与
 * 意图识别的配合，正是"快慢车道"里规则引擎擅长的事：固定套路的话直接命中。
 */
public final class SlotExtractor {

    private SlotExtractor() {
    }

    private static final List<String> CITIES = List.of(
            "北京", "上海", "广州", "深圳", "杭州", "成都", "重庆", "西安", "武汉", "南京",
            "苏州", "厦门", "青岛", "大连", "昆明", "贵阳", "拉萨", "乌鲁木齐", "哈尔滨", "长沙",
            "合肥", "郑州", "天津", "宁波", "无锡", "福州", "济南", "太原", "石家庄", "海口",
            "南宁", "兰州", "西宁", "银川", "呼和浩特", "沈阳", "长春", "南昌", "佛山", "东莞",
            "珠海", "惠州", "中山", "台州", "温州", "泉州", "常州", "徐州", "三亚", "丽江",
            "大理", "桂林", "黄山", "九江", "赣州", "洛阳", "唐山", "秦皇岛", "威海", "烟台");

    private static final Pattern ROUTE_ARROW = Pattern.compile("([\\u4e00-\\u9fa5]{2,3})(?:到|去|→|->|往|飞)([\\u4e00-\\u9fa5]{2,3})");
    private static final Pattern DATE_EXPLICIT = Pattern.compile("((?:19|20)\\d{2}[年\\-/]\\d{1,2}[月\\-/]\\d{1,2}日?)");
    private static final Pattern DATE_MD = Pattern.compile("(\\d{1,2}月\\d{1,2}日)");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 抽取槽位（缺失字段为 null）。 */
    public static Map<String, String> extract(String query, LocalDate today) {
        Map<String, String> slots = new LinkedHashMap<>();
        String dep = null;
        String dst = null;

        Matcher m = ROUTE_ARROW.matcher(query);
        if (m.find() && CITIES.contains(m.group(1)) && CITIES.contains(m.group(2))) {
            dep = m.group(1);
            dst = m.group(2);
        } else {
            for (String city : CITIES) {
                if (!query.contains(city)) {
                    continue;
                }
                if (dst == null || query.indexOf(city) > query.indexOf(dst)) {
                    dst = city;
                }
            }
        }

        String date = null;
        if (query.contains("大后天")) {
            date = today.plusDays(3).format(DATE_FMT);
        } else if (query.contains("后天")) {
            date = today.plusDays(2).format(DATE_FMT);
        } else if (query.contains("明天")) {
            date = today.plusDays(1).format(DATE_FMT);
        } else if (query.contains("今天")) {
            date = today.format(DATE_FMT);
        } else {
            Matcher em = DATE_EXPLICIT.matcher(query);
            if (em.find()) {
                date = em.group(1).replace('年', '-').replace('月', '-').replace('日', ' ').trim();
            } else {
                Matcher mm = DATE_MD.matcher(query);
                if (mm.find()) {
                    String md = mm.group(1).replace('月', '-').replace('日', ' ').trim();
                    date = today.getYear() + "-" + md;
                }
            }
        }

        slots.put("departure", dep);
        slots.put("destination", dst);
        slots.put("date", date);
        slots.put("budget", extractBudget(query));
        return slots;
    }

    private static String extractBudget(String query) {
        Matcher bm = Pattern.compile("(\\d{3,6})\\s*(?:元|块)?\\s*预算").matcher(query);
        if (bm.find()) {
            return bm.group(1);
        }
        Matcher bp = Pattern.compile("预算\\s*(\\d{3,6})").matcher(query);
        return bp.find() ? bp.group(1) : null;
    }

    /** 补全缺失的必要槽位后的展示文本（演示消歧补全的可见性）。 */
    public static String shortSlots(Map<String, String> slots) {
        return String.format("出发=%s 到达=%s 日期=%s 预算=%s",
                slots.get("departure"), slots.get("destination"), slots.get("date"),
                slots.get("budget") == null ? "未给定" : slots.get("budget"));
    }
}
