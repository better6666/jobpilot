package com.jobpilot.browser;

import com.microsoft.playwright.Page;

import java.util.List;

/**
 * 点了"申请/投递"之后看页面上发生了什么。58 同城、拉勾共用。
 *
 * <p>这两个平台的投递后流程都没法在不真投的情况下观察，所以驱动每次点完都用
 * {@link #overlayShape} 把浮层记进日志，第一次真投后据此校准。
 */
public final class PageProbe {

    private PageProbe() {
    }

    /** 当前浮在页面上的弹层（文字 + 按钮），最多三个；没有返回 null */
    public static String overlayShape(Page page) {
        try {
            Object o = page.evaluate("() => { const out = [];"
                    + " for (const e of document.querySelectorAll('div, section')) {"
                    + "  const st = getComputedStyle(e);"
                    + "  if (!(st.position === 'fixed' || st.position === 'absolute') || e.offsetParent === null) continue;"
                    + "  if (parseInt(st.zIndex || '0') < 10 || e.getBoundingClientRect().width < 150) continue;"
                    + "  const text = (e.innerText || '').replace(/\\s+/g, ' ').trim(); if (text.length < 2) continue;"
                    + "  const btns = [...e.querySelectorAll('a, button, span')].filter(b => b.children.length === 0)"
                    + "    .map(b => (b.innerText || '').trim()).filter(t => t && t.length < 10).slice(0, 8);"
                    + "  out.push(e.className.toString().slice(0, 40) + ' :: ' + text.slice(0, 120) + ' :: ' + btns.join('/'));"
                    + "  if (out.length >= 3) break; }"
                    + " return out.length ? out.join(' || ') : null; }");
            return o == null ? null : String.valueOf(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** 弹层里文字含"简历/申请/投递"的，点它的主按钮（投递/确定/确认…）一次 */
    public static boolean clickConfirm(Page page) {
        try {
            Object clicked = page.evaluate("() => {"
                    + " const re = /^(投递|确定|确认|确认投递|立即投递|确认申请|投递简历|发送)$/;"
                    + " for (const e of document.querySelectorAll('div, section')) {"
                    + "  const st = getComputedStyle(e);"
                    + "  if (!(st.position === 'fixed' || st.position === 'absolute') || e.offsetParent === null) continue;"
                    + "  if (parseInt(st.zIndex || '0') < 10) continue;"
                    + "  if (!/简历|申请|投递/.test(e.innerText || '')) continue;"
                    + "  const b = [...e.querySelectorAll('a, button, span')].filter(x => x.children.length === 0 && x.offsetParent !== null)"
                    + "    .find(x => re.test((x.innerText || '').trim()));"
                    + "  if (b) { b.click(); return true; } }"
                    + " return false; }");
            return Boolean.TRUE.equals(clicked);
        } catch (Exception e) {
            return false;
        }
    }

    /** 页面正文里出现了哪一个关键词；都没有返回 null */
    public static String bodyHit(Page page, String... needles) {
        try {
            Object hit = page.evaluate("ks => { const b = document.body ? document.body.innerText : '';"
                    + " return ks.find(k => b.includes(k)) || null; }", List.of(needles));
            return hit == null ? null : String.valueOf(hit);
        } catch (Exception e) {
            return null;
        }
    }
}
