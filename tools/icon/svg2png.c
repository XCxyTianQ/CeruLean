/*
 * svg2png —— 极简 SVG → PNG 栅格化器（cairo）
 *
 * 为什么需要它：本机没有 rsvg-convert / inkscape / imagemagick / cairosvg / PIL，
 * 但有 cairo + freetype + fontconfig + gcc。与其占用用户的浏览器标签页截图，
 * 不如用一个只支持"我们实际用到的 SVG 子集"的小工具在本机渲染：
 *   <rect> <circle> <line> <path d="M L C A Z"> <text>
 * 不支持（也不需要）：transform / group / gradient / filter / CSS。
 *
 * 用法: svg2png <input.svg> <output.png> [size]
 */
#include <cairo.h>
#include <ctype.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

static char *slurp(const char *path, long *out_len) {
    FILE *f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "无法打开 %s\n", path); return NULL; }
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    char *buf = malloc((size_t)n + 1);
    if (fread(buf, 1, (size_t)n, f) != (size_t)n) { fclose(f); free(buf); return NULL; }
    buf[n] = 0; fclose(f);
    if (out_len) *out_len = n;
    return buf;
}

/* 在 tag 文本里取属性值（双引号） */
static int attr(const char *tag, const char *name, char *out, size_t n) {
    size_t nl = strlen(name);
    const char *p = tag;
    while ((p = strstr(p, name)) != NULL) {
        /* 要求前面是空白或标签内空白，后面允许 = 与空白 */
        if (p != tag && !isspace((unsigned char)p[-1]) && p[-1] != '<') { p += nl; continue; }
        const char *q = p + nl;
        while (*q == ' ' || *q == '\t') q++;
        if (*q != '=') { p += nl; continue; }
        q++;
        while (*q == ' ' || *q == '\t') q++;
        if (*q != '"') { p += nl; continue; }
        q++;
        const char *e = strchr(q, '"');
        if (!e) return 0;
        size_t len = (size_t)(e - q); if (len >= n) len = n - 1;
        memcpy(out, q, len); out[len] = 0;
        return 1;
    }
    return 0;
}

static double num(const char *tag, const char *name, double def) {
    char buf[64];
    return attr(tag, name, buf, sizeof buf) ? atof(buf) : def;
}

static int is_none(const char *tag, const char *name) {
    char buf[64];
    return attr(tag, name, buf, sizeof buf) && strcmp(buf, "none") == 0;
}

static void set_fill(cairo_t *cr, const char *tag) {
    char c[64];
    if (!attr(tag, "fill", c, sizeof c)) { cairo_set_source_rgb(cr, 0, 0, 0); return; }
    if (strcmp(c, "none") == 0) { cairo_set_source_rgba(cr, 0, 0, 0, 0); return; }
    if (c[0] == '#') {
        unsigned int v = (unsigned int)strtoul(c + 1, NULL, 16);
        double r = ((v >> 16) & 0xFF) / 255.0, g = ((v >> 8) & 0xFF) / 255.0, b = (v & 0xFF) / 255.0;
        cairo_set_source_rgb(cr, r, g, b);
    } else if (strcmp(c, "white") == 0) cairo_set_source_rgb(cr, 1, 1, 1);
    else cairo_set_source_rgb(cr, 0, 0, 0);
}

static void set_stroke(cairo_t *cr, const char *tag) {
    char c[64];
    if (!attr(tag, "stroke", c, sizeof c) || strcmp(c, "none") == 0) { cairo_set_source_rgba(cr, 0, 0, 0, 0); return; }
    if (c[0] == '#') {
        unsigned int v = (unsigned int)strtoul(c + 1, NULL, 16);
        cairo_set_source_rgb(cr, ((v >> 16) & 0xFF) / 255.0, ((v >> 8) & 0xFF) / 255.0, (v & 0xFF) / 255.0);
    } else if (strcmp(c, "white") == 0) cairo_set_source_rgb(cr, 1, 1, 1);
    else cairo_set_source_rgb(cr, 0, 0, 0);
}

static const char *skip_sep(const char *p) {
    while (*p && (isspace((unsigned char)*p) || *p == ',')) p++;
    return p;
}

/* SVG 椭圆弧 → 三次贝塞尔（按 SVG 规范 F.6.5） */
static void arc_to_beziers(cairo_t *cr, double x1, double y1, double rx, double ry,
                           double phi_deg, int fa, int fs, double x2, double y2) {
    double phi = phi_deg * M_PI / 180.0, cosp = cos(phi), sinp = sin(phi);
    double dx = (x1 - x2) / 2.0, dy = (y1 - y2) / 2.0;
    double x1p = cosp * dx + sinp * dy, y1p = -sinp * dx + cosp * dy;
    rx = fabs(rx); ry = fabs(ry);
    if (rx == 0 || ry == 0) { cairo_line_to(cr, x2, y2); return; }
    double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
    if (lambda > 1) { double s = sqrt(lambda); rx *= s; ry *= s; }
    double sign = (fa != fs) ? 1.0 : -1.0;
    double n = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
    double d = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
    double co = sign * sqrt(fmax(0.0, n / d));
    double cxp = co * (rx * y1p / ry), cyp = co * (-ry * x1p / rx);
    double cx = cosp * cxp - sinp * cyp + (x1 + x2) / 2.0;
    double cy = sinp * cxp + cosp * cyp + (y1 + y2) / 2.0;
    double th1 = atan2((y1p - cyp) / ry, (x1p - cxp) / rx);
    double th2 = atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx);
    double dth = th2 - th1;
    if (!fs && dth > 0) dth -= 2 * M_PI;
    if (fs && dth < 0) dth += 2 * M_PI;
    int segs = (int)ceil(fabs(dth) / (M_PI / 2.0));
    if (segs < 1) segs = 1;
    double delta = dth / segs, t = 4.0 / 3.0 * tan(delta / 4.0), th = th1;
    for (int i = 0; i < segs; i++) {
        double c1 = cos(th), s1 = sin(th), c2 = cos(th + delta), s2 = sin(th + delta);
        double px1 = rx * c1, py1 = ry * s1, px2 = rx * c2, py2 = ry * s2;
        double d1x = -rx * s1, d1y = ry * c1, d2x = -rx * s2, d2y = ry * c2;
        double k1x = px1 + t * d1x, k1y = py1 + t * d1y;
        double k2x = px2 - t * d2x, k2y = py2 - t * d2y;
#define TX(px, py) (cosp * (px) - sinp * (py) + cx)
#define TY(px, py) (sinp * (px) + cosp * (py) + cy)
        cairo_curve_to(cr, TX(k1x, k1y), TY(k1x, k1y), TX(k2x, k2y), TY(k2x, k2y), TX(px2, py2), TY(px2, py2));
        th += delta;
    }
}

static void build_path(cairo_t *cr, const char *d) {
    const char *p = d;
    char cmd = 0;
    double cx = 0, cy = 0, sx = 0, sy = 0;
    while (*p) {
        p = skip_sep(p);
        if (!*p) break;
        if (isalpha((unsigned char)*p)) { cmd = *p++; continue; }
        double a, b, c, e, f, g, h;
        switch (cmd) {
            case 'M': a = strtod(p, (char **)&p); p = skip_sep(p); b = strtod(p, (char **)&p);
                      cairo_move_to(cr, a, b); cx = sx = a; cy = sy = b; cmd = 'L'; break;
            case 'm': a = strtod(p, (char **)&p); p = skip_sep(p); b = strtod(p, (char **)&p);
                      cairo_move_to(cr, cx + a, cy + b); cx = sx = cx + a; cy = sy = cy + b; cmd = 'l'; break;
            case 'L': a = strtod(p, (char **)&p); p = skip_sep(p); b = strtod(p, (char **)&p);
                      cairo_line_to(cr, a, b); cx = a; cy = b; break;
            case 'l': a = strtod(p, (char **)&p); p = skip_sep(p); b = strtod(p, (char **)&p);
                      cairo_line_to(cr, cx + a, cy + b); cx += a; cy += b; break;
            case 'C': a = strtod(p, (char **)&p); p = skip_sep(p); b = strtod(p, (char **)&p); p = skip_sep(p);
                      c = strtod(p, (char **)&p); p = skip_sep(p); e = strtod(p, (char **)&p); p = skip_sep(p);
                      f = strtod(p, (char **)&p); p = skip_sep(p); g = strtod(p, (char **)&p);
                      cairo_curve_to(cr, a, b, c, e, f, g); cx = f; cy = g; break;
            case 'A': a = strtod(p, (char **)&p); p = skip_sep(p); b = strtod(p, (char **)&p); p = skip_sep(p);
                      c = strtod(p, (char **)&p); p = skip_sep(p); e = strtod(p, (char **)&p); p = skip_sep(p);
                      f = strtod(p, (char **)&p); p = skip_sep(p); g = strtod(p, (char **)&p); p = skip_sep(p);
                      h = strtod(p, (char **)&p);
                      arc_to_beziers(cr, cx, cy, a, b, c, (int)e, (int)f, g, h); cx = g; cy = h; break;
            case 'Z': case 'z': cairo_close_path(cr); cx = sx; cy = sy; break;
            default: p++; break;
        }
    }
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "用法: %s <in.svg> <out.png> [size]\n", argv[0]); return 2; }
    long len = 0;
    char *svg = slurp(argv[1], &len);
    if (!svg) return 1;

    double svgW = 1024, svgH = 1024;
    {   /* 从 <svg ...> 读 width/height */
        char *s = strstr(svg, "<svg");
        if (s) { char *gt = strchr(s, '>'); if (gt) { char save = *gt; *gt = 0;
                 svgW = num(s, "width", svgW); svgH = num(s, "height", svgH); *gt = save; } }
    }
    int size = (argc > 3) ? atoi(argv[3]) : (int)svgW;
    if (size <= 0) size = (int)svgW;

    cairo_surface_t *surf = cairo_image_surface_create(CAIRO_FORMAT_ARGB32, size, size);
    cairo_t *cr = cairo_create(surf);
    double sc = (double)size / svgW;
    cairo_scale(cr, sc, sc);
    if (svgH != svgW) fprintf(stderr, "警告: 非正方形 viewBox（将按宽度缩放）\n");

    const char *p = svg;
    while ((p = strchr(p, '<')) != NULL) {
        p++;
        if (*p == '!' || *p == '?' || *p == '/') { const char *e = strchr(p, '>'); if (!e) break; p = e + 1; continue; }
        char name[32]; int i = 0;
        while (*p && (isalnum((unsigned char)*p) || *p == '-') && i < 31) name[i++] = *p++;
        name[i] = 0;
        const char *gt = strchr(p, '>');
        if (!gt) break;
        char *tag = strndup(p, (size_t)(gt - p));

        if (strcmp(name, "rect") == 0) {
            cairo_rectangle(cr, num(tag, "x", 0), num(tag, "y", 0), num(tag, "width", 0), num(tag, "height", 0));
            set_fill(cr, tag); cairo_fill(cr);
        } else if (strcmp(name, "circle") == 0) {
            cairo_arc(cr, num(tag, "cx", 0), num(tag, "cy", 0), num(tag, "r", 0), 0, 2 * M_PI);
            set_fill(cr, tag);
            if (attr(tag, "stroke", (char[8]){0}, 8) && !is_none(tag, "stroke")) cairo_fill_preserve(cr); else cairo_fill(cr);
            if (!is_none(tag, "stroke")) {
                set_stroke(cr, tag);
                cairo_set_line_width(cr, num(tag, "stroke-width", 1));
                cairo_stroke(cr);
            }
        } else if (strcmp(name, "line") == 0) {
            cairo_move_to(cr, num(tag, "x1", 0), num(tag, "y1", 0));
            cairo_line_to(cr, num(tag, "x2", 0), num(tag, "y2", 0));
            set_stroke(cr, tag);
            cairo_set_line_width(cr, num(tag, "stroke-width", 1));
            char cap[16];
            if (attr(tag, "stroke-linecap", cap, sizeof cap) && strcmp(cap, "round") == 0)
                cairo_set_line_cap(cr, CAIRO_LINE_CAP_ROUND);
            cairo_stroke(cr);
        } else if (strcmp(name, "path") == 0) {
            char d[4096];
            if (attr(tag, "d", d, sizeof d)) {
                cairo_new_path(cr);
                build_path(cr, d);
                int has_stroke = attr(tag, "stroke", (char[8]){0}, 8) && !is_none(tag, "stroke");
                if (has_stroke) { set_fill(cr, tag); cairo_fill_preserve(cr);
                                  set_stroke(cr, tag);
                                  cairo_set_line_width(cr, num(tag, "stroke-width", 1));
                                  cairo_stroke(cr); }
                else { set_fill(cr, tag); cairo_fill(cr); }
            }
        } else if (strcmp(name, "text") == 0) {
            const char *close = strstr(gt, "</text>");
            if (close) {
                char *content = strndup(gt + 1, (size_t)(close - gt - 1));
                char fam[128] = "sans-serif";
                char famattr[256];
                if (attr(tag, "font-family", famattr, sizeof famattr)) {
                    char *comma = strchr(famattr, ',');
                    if (comma) *comma = 0;
                    while (*famattr == ' ') memmove(famattr, famattr + 1, strlen(famattr));
                    snprintf(fam, sizeof fam, "%s", famattr);
                }
                double weight = num(tag, "font-weight", 400);
                cairo_select_font_face(cr, fam, CAIRO_FONT_SLANT_NORMAL,
                                       weight >= 600 ? CAIRO_FONT_WEIGHT_BOLD : CAIRO_FONT_WEIGHT_NORMAL);
                cairo_set_font_size(cr, num(tag, "font-size", 16));
                double x = num(tag, "x", 0), y = num(tag, "y", 0);
                char anchor[16] = "";
                attr(tag, "text-anchor", anchor, sizeof anchor);
                cairo_text_extents_t ex;
                cairo_text_extents(cr, content, &ex);
                if (strcmp(anchor, "middle") == 0) x -= ex.width / 2 + ex.x_bearing;
                else if (strcmp(anchor, "end") == 0) x -= ex.width + ex.x_bearing;
                set_fill(cr, tag);
                cairo_move_to(cr, x, y);
                cairo_show_text(cr, content);
                free(content);
            }
        }
        free(tag);
        p = gt + 1;
    }

    cairo_destroy(cr);
    cairo_surface_flush(surf);
    cairo_status_t st = cairo_surface_write_to_png(surf, argv[2]);
    cairo_surface_destroy(surf);
    free(svg);
    if (st != CAIRO_STATUS_SUCCESS) { fprintf(stderr, "写 PNG 失败: %s\n", cairo_status_to_string(st)); return 1; }
    printf("已渲染 %s (%d×%d)\n", argv[2], size, size);
    return 0;
}
