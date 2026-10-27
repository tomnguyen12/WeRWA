package vn.werwa.governance.calendar;

import java.time.LocalDate;

/**
 * Đổi ngày âm lịch Việt Nam sang dương lịch theo thuật toán thiên văn của Hồ Ngọc Đức, múi giờ UTC+7. Chỉ dùng cho các ngày
 * lễ âm lịch (Tết, Giỗ Tổ Hùng Vương).
 */
final class LunarCalendar {

    private static final double TZ = 7.0;
    private static final double DR = Math.PI / 180;

    private LunarCalendar() {}

    /** Ngày dương lịch của ngày {@code day} tháng {@code month} (không nhuận) năm âm lịch {@code year}. */
    static LocalDate toSolar(int day, int month, int year) {
        int a11;
        int b11;
        if (month < 11) {
            a11 = lunarMonth11(year - 1);
            b11 = lunarMonth11(year);
        } else {
            a11 = lunarMonth11(year);
            b11 = lunarMonth11(year + 1);
        }
        int k = (int) Math.floor(0.5 + (a11 - 2415021.076998695) / 29.530588853);
        int off = month - 11;
        if (off < 0) {
            off += 12;
        }
        if (b11 - a11 > 365) {
            int leapOff = leapMonthOffset(a11);
            if (off >= leapOff) {
                off += 1;
            }
        }
        int monthStart = newMoonDay(k + off);
        return LocalDate.ofEpochDay(monthStart + day - 1 - 2440588L);
    }

    static long julianDay(int dd, int mm, int yy) {
        int a = (14 - mm) / 12;
        int y = yy + 4800 - a;
        int m = mm + 12 * a - 3;
        long jd = dd + (153L * m + 2) / 5 + 365L * y + y / 4 - y / 100 + y / 400 - 32045;
        if (jd < 2299161) {
            jd = dd + (153L * m + 2) / 5 + 365L * y + y / 4 - 32083;
        }
        return jd;
    }

    static int newMoonDay(int k) {
        double t = k / 1236.85;
        double t2 = t * t;
        double t3 = t2 * t;
        double jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3;
        jd1 += 0.00033 * Math.sin((166.56 + 132.87 * t - 0.009173 * t2) * DR);
        double m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3;
        double mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3;
        double f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3;
        double c1 = (0.1734 - 0.000393 * t) * Math.sin(m * DR) + 0.0021 * Math.sin(2 * DR * m);
        c1 = c1 - 0.4068 * Math.sin(mpr * DR) + 0.0161 * Math.sin(DR * 2 * mpr);
        c1 = c1 - 0.0004 * Math.sin(DR * 3 * mpr);
        c1 = c1 + 0.0104 * Math.sin(DR * 2 * f) - 0.0051 * Math.sin(DR * (m + mpr));
        c1 = c1 - 0.0074 * Math.sin(DR * (m - mpr)) + 0.0004 * Math.sin(DR * (2 * f + m));
        c1 = c1 - 0.0004 * Math.sin(DR * (2 * f - m)) - 0.0006 * Math.sin(DR * (2 * f + mpr));
        c1 = c1 + 0.0010 * Math.sin(DR * (2 * f - mpr)) + 0.0005 * Math.sin(DR * (2 * mpr + m));
        double deltat = t < -11
                ? 0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3
                : -0.000278 + 0.000265 * t + 0.000262 * t2;
        return (int) Math.floor(jd1 + c1 - deltat + 0.5 + TZ / 24);
    }

    static int sunLongitude(int jdn) {
        double t = (jdn - 2451545.5 - TZ / 24) / 36525;
        double t2 = t * t;
        double m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2;
        double l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2;
        double dl = (1.914600 - 0.004817 * t - 0.000014 * t2) * Math.sin(DR * m);
        dl += (0.019993 - 0.000101 * t) * Math.sin(DR * 2 * m) + 0.000290 * Math.sin(DR * 3 * m);
        double l = (l0 + dl) * DR;
        l = l - Math.PI * 2 * Math.floor(l / (Math.PI * 2));
        return (int) Math.floor(l / Math.PI * 6);
    }

    static int lunarMonth11(int year) {
        double off = julianDay(31, 12, year) - 2415021;
        int k = (int) Math.floor(off / 29.530588853);
        int nm = newMoonDay(k);
        if (sunLongitude(nm) >= 9) {
            nm = newMoonDay(k - 1);
        }
        return nm;
    }

    static int leapMonthOffset(int a11) {
        int k = (int) Math.floor((a11 - 2415021.076998695) / 29.530588853 + 0.5);
        int last;
        int i = 1;
        int arc = sunLongitude(newMoonDay(k + i));
        do {
            last = arc;
            i++;
            arc = sunLongitude(newMoonDay(k + i));
        } while (arc != last && i < 14);
        return i - 1;
    }
}
