package ru.archeonzero.azchat

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.animation.AnimationUtils
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.util.Base64
import android.webkit.JavascriptInterface
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: ZaiSwipeRefresh
    private lateinit var progressBar: ProgressBar
    private lateinit var fabHome: FloatingActionButton
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private val targetUrl = "https://chat.z.ai"
    private var popupWebView: WebView? = null
    private var popupContainer: FrameLayout? = null
    private var pendingGoogleAuthUrl: String? = null
    private var pendingGoogleTarget: WebView? = null
    /** Сколько крупных оверлеев-вложений сейчас открыто (приходит из JS). */
    private var attachmentOverlayCount = 0
    /** Автовыбор модели — не чаще одного раза за запуск приложения. */
    private var modelAutoSelectInjected = false

    /**
     * === Проблема №7: сайт подставляет glm-5.2 вместо последней модели ===
     *
     * true — после каждой загрузки страницы JS находит селектор моделей
     * (по тексту «GLM-x.x»), открывает список и кликает модель с максимальной
     * версией. Срабатывает только в первые ~5 секунд после загрузки страницы,
     * только когда открыта страница чата и нет модальных диалогов.
     *
     * Поставьте false, если хотите управлять моделью вручную — тогда
     * обёртка вообще не трогает селектор.
     */
    private val autoSelectLatestModel = true

    /**
     * Chrome-подобный User-Agent.
     *
     * Сайт Google OAuth сравнивает UA с "известными" браузерами. Если видит
     * стандартный WebView UA (с пометкой "wv" или "Version/4.0"), он часто
     * решает "это не настоящий браузер, нет доступа к AccountManager" и
     * показывает веб-форму ввода логина/пароля вместо системного селектора
     * аккаунтов Android.
     *
     * Подмена UA на современный Chrome Android убеждает Google показать
     * нативный flow с выбором аккаунтов, уже зарегистрированных на устройстве.
     */
    private val chromeLikeUserAgent =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.6478.134 Mobile Safari/537.36"

    /**
     * JS that surgically hides the site's headers/footers/banners so the WebView
     * feels like a 100% native app. The interval re-applies after SPA route changes.
     */
    private val hideChromeJs = """
        (function() {
            function hideChrome() {
                if (!document.body) return;
                var selectors = [
                    'header', 'footer', 'nav.topbar', 'nav[class*="topbar"]',
                    '[class*="DesktopHeader"]', '[class*="MobileHeader"]',
                    '[class*="SiteHeader"]', '[class*="SiteFooter"]',
                    '[class*="AppHeader"]', '[class*="AppFooter"]',
                    '[class*="promo-banner"]', '[class*="announcement"]',
                    '[id*="header"]', '[id*="footer"]'
                ];
                selectors.forEach(function(sel) {
                    document.querySelectorAll(sel).forEach(function(el) {
                        if (el.offsetHeight < 220) {
                            el.style.setProperty('display', 'none', 'important');
                        }
                    });
                });
                document.body.style.background = '#000';
            }
            hideChrome();
            var observer = new MutationObserver(function() { hideChrome(); });
            observer.observe(document.body, { childList: true, subtree: true });
            setInterval(hideChrome, 2000);

            // === Проблема 1: отчёт о внутреннем скролле ===
            // Чат скроллится внутри внутреннего div, WebView.scrollY всегда 0.
            // Через JS-мост сообщаем в ZaiSwipeRefresh, прокручен ли контейнер —
            // чтобы pull-to-refresh не перехватывал свайп в истории сообщений.
            function findInnerScrollTop() {
                var best = document.documentElement.scrollTop || window.pageYOffset || 0;
                var all = document.querySelectorAll('div, main, section');
                for (var i = 0; i < all.length && i < 300; i++) {
                    var el = all[i];
                    if (el.scrollHeight > el.clientHeight + 20) {
                        if (el.scrollTop > best) best = el.scrollTop;
                    }
                }
                return Math.round(best);
            }
            function reportScroll() {
                try { ZaiScrollBridge.reportScroll(findInnerScrollTop()); } catch(e) {}
            }
            window.addEventListener('scroll', reportScroll, true);
            setInterval(reportScroll, 400);

            // === Проблема 3: вложения ===
            // Сайт сам открывает панель с вложениями при входе в чат — такие
            // автоокна закрываем. А всё, что открыто после осознанного жеста
            // уже внутри чата (тап по миниатюре, документу, превью, Share,
            // ссылке), оставляем. Жест входа в чат (тап по строке/аватару)
            // осознанным не считается.
            function attachmentOverlays() {
                var all = document.querySelectorAll(
                    '[role="dialog"], [class*="preview"], [class*="Preview"], ' +
                    '[class*="lightbox"], [class*="Lightbox"], [class*="gallery"], ' +
                    '[class*="Gallery"], [class*="overlay"], [class*="Overlay"], ' +
                    '[class*="attachment"], [class*="Attachment"], ' +
                    '[class*="file-view"], [class*="FileView"], [class*="viewer"], ' +
                    '[class*="Viewer"], [class*="modal"], [class*="Modal"], ' +
                    '[class*="drawer"], [class*="Drawer"], [class*="sheet"], ' +
                    '[class*="Sheet"], [class*="side-panel"], [class*="SidePanel"], ' +
                    '[class*="file-list"], [class*="FileList"]'
                );
                var out = [];
                for (var i = 0; i < all.length && i < 400; i++) {
                    var el = all[i];
                    if (!el || el === document.body || el === document.documentElement) continue;
                    // Не трогаем контейнер, внутри которого находится сам чат.
                    if (el.querySelector('textarea, [contenteditable="true"]')) continue;
                    var rect = el.getBoundingClientRect();
                    if (rect.width < 80 || rect.height < 80) continue;
                    var style = window.getComputedStyle(el);
                    if (style.display === 'none' || style.visibility === 'hidden') continue;
                    var cls = (el.className && el.className.toString) ? el.className.toString() : '';
                    var strong = /attachment|file-?view|preview|lightbox|gallery|drawer|sheet|viewer|modal|file-list/i.test(cls);
                    var minW = strong ? 0.25 : 0.6;
                    var minH = strong ? 0.25 : 0.55;
                    if (rect.width < window.innerWidth * minW || rect.height < window.innerHeight * minH) continue;
                    var isOverlayLike = style.position === 'fixed' || style.position === 'absolute';
                    if (!isOverlayLike && !strong) continue;
                    var media = el.querySelector('img, video, canvas, iframe');
                    if (media || strong || /dialog/i.test(el.getAttribute('role') || '')) out.push(el);
                }
                // Оставляем только внешние оверлеи — вложенные не считаем дважды.
                return out.filter(function(el) {
                    for (var i = 0; i < out.length; i++) {
                        if (out[i] !== el && out[i].contains(el)) return false;
                    }
                    return true;
                });
            }

            // Осознанное действие — это ЛЮБОЙ тап уже внутри чата: по
            // миниатюре, документу, превью, Share, ссылке. Раньше здесь
            // стояли узкие селекторы, и тап по миниатюре z.ai мимо них
            // пролетал — превью тут же закрывалось. Широкое сравнение
            // ниже страхует и от смены разметки на сайте.
            function looksLikeAttachment(target) {
                if (!target || !target.closest) return false;
                return !!target.closest(
                    'a[download], img, video, canvas, ' +
                    '[class*="attachment" i], [class*="preview" i], ' +
                    '[class*="lightbox" i], [class*="gallery" i], ' +
                    '[class*="viewer" i], [class*="file" i], ' +
                    '[class*="image" i], [class*="img" i], [class*="media" i], ' +
                    '[class*="photo" i], [class*="thumb" i], [class*="upload" i], ' +
                    '[class*="doc" i], [class*="share" i], ' +
                    '[aria-label*="share" i], [title*="share" i], ' +
                    '[aria-label*="Поделиться"], [title*="Поделиться"], ' +
                    '[data-testid*="file" i], [data-testid*="image" i], ' +
                    '[role="dialog"]'
                );
            }


            // Спрятанные нами окна нельзя «убивать» навсегда: если сайт
            // переиспользует тот же узел под превью, оно останется скрытым.
            // Запоминаем такие узлы и возвращаем им display при следующем
            // осознанном тапе по вложению.
            var hiddenByUs = [];
            function hideByUs(el) {
                try {
                    if (!el || !el.style) return;
                    if (!el.__zaiHiddenByUs) {
                        el.__zaiHiddenByUs = true;
                        hiddenByUs.push(el);
                    }
                    el.style.setProperty('display', 'none', 'important');
                } catch (e) {}
            }
            function restoreHiddenByUs() {
                for (var i = 0; i < hiddenByUs.length; i++) {
                    var el = hiddenByUs[i];
                    try {
                        if (el && el.style) el.style.removeProperty('display');
                        if (el) el.__zaiHiddenByUs = false;
                    } catch (e) {}
                }
                hiddenByUs = [];
            }
            function pruneHiddenByUs() {
                var alive = [];
                for (var i = 0; i < hiddenByUs.length; i++) {
                    var el = hiddenByUs[i];
                    if (el && el.isConnected) alive.push(el);
                }
                hiddenByUs = alive;
            }

            function closeAttachmentOverlay(el) {
                var btn = el.querySelector(
                    'button[aria-label*="close" i], button[aria-label*="закрыть" i], ' +
                    'button[title*="close" i], button[title*="закрыть" i], ' +
                    'button[class*="close"], [class*="CloseButton"], ' +
                    '[class*="close-btn"], [class*="closeIcon"]'
                );
                if (btn) {
                    try { btn.click(); return true; } catch (e) {}
                }
                hideByUs(el);
                // Если у панели есть отдельный backdrop — прячем и его.
                var all = document.querySelectorAll('div, section');
                for (var i = 0; i < all.length && i < 400; i++) {
                    var c = all[i];
                    if (c === el || c.contains(el) || el.contains(c)) continue;
                    var r = c.getBoundingClientRect();
                    if (r.width < window.innerWidth * 0.9 || r.height < window.innerHeight * 0.9) continue;
                    var cs = window.getComputedStyle(c);
                    if (cs.position !== 'fixed' && cs.position !== 'absolute') continue;
                    if (c.querySelector('textarea, [contenteditable="true"]')) continue;
                    var cc = (c.className && c.className.toString) ? c.className.toString() : '';
                    if (/backdrop|overlay|mask|scrim/i.test(cc)) {
                        hideByUs(c);
                    }
                }
                return true;
            }

            // Сколько ждать после входа в чат, прежде чем жесты снова
            // считать осознанными; и сколько живёт «окно доверия».
            var CHAT_SETTLE_MS = 700;
            var POST_ROUTE_STRICT_MS = 4000;
            var INTENT_WINDOW_MS = 4000;

            var intentUntil = 0;
            var allowedOverlays = (typeof WeakSet !== 'undefined') ? new WeakSet() : null;

            function noteGesture(evt) {
                var now = Date.now();
                var sinceRoute = now - lastRouteChangeAt;
                // Жест, которым вошли в чат (тап по строке/аватару), не в счёт.
                if (sinceRoute < CHAT_SETTLE_MS) return;
                var attachTap = looksLikeAttachment(evt.target);
                // Первые секунды после входа в чат засчитываем только тап
                // по вложению: сайт ещё может дорисовать авто-панель.
                if (sinceRoute < POST_ROUTE_STRICT_MS && !attachTap) return;
                intentUntil = now + INTENT_WINDOW_MS;
                if (attachTap) {
                    restoreHiddenByUs();
                    var ov = evt.target.closest && evt.target.closest(
                        '[role="dialog"], [class*="preview" i], [class*="lightbox" i], ' +
                        '[class*="gallery" i], [class*="viewer" i], ' +
                        '[class*="attachment" i], [class*="sheet" i], ' +
                        '[class*="modal" i], [class*="overlay" i]'
                    );
                    if (ov && allowedOverlays) allowedOverlays.add(ov);
                }
            }
            ['pointerdown', 'touchstart', 'mousedown'].forEach(function(evt) {
                document.addEventListener(evt, noteGesture, true);
            });
            document.addEventListener('keydown', function(evt) {
                if (evt.key === 'Escape') {
                    try { window.__zaiHideAttachments(); } catch (e) {}
                }
            }, true);

            // Переход в конкретный чат (/c/<uuid>) — главный триггер
            // автооткрытия панели вложений. В это окно закрываем панель
            // даже если перед этим был тап по чату/аватару.
            var lastRouteChangeAt = 0;
            var lastHref = location.href;
            function isChatRoute() {
                return /\/c\/[0-9a-f-]{8,}/i.test(location.href);
            }
            function markRouteChange() {
                lastHref = location.href;
                if (isChatRoute()) {
                    lastRouteChangeAt = Date.now();
                    // Вход в чат: тап по строке чата осознанным не считается.
                    intentUntil = 0;
                }
            }
            ['pushState', 'replaceState'].forEach(function(fn) {
                try {
                    var orig = history[fn];
                    history[fn] = function() {
                        var result = orig.apply(this, arguments);
                        markRouteChange();
                        return result;
                    };
                } catch (e) {}
            });
            window.addEventListener('popstate', markRouteChange);
            setInterval(function() {
                if (location.href !== lastHref) markRouteChange();
            }, 500);
            if (isChatRoute()) lastRouteChangeAt = Date.now();

            function suppressAutoPreviews() {
                var list = attachmentOverlays();
                var now = Date.now();
                var intentRecent = intentUntil > now;
                for (var i = 0; i < list.length; i++) {
                    var el = list[i];
                    if (allowedOverlays && allowedOverlays.has(el)) continue;
                    // Пользователь сам открыл превью/поделиться — оставляем.
                    if (intentRecent) {
                        if (allowedOverlays) allowedOverlays.add(el);
                        continue;
                    }
                    // Автооткрытие (в т.ч. при входе в чат) — закрываем.
                    closeAttachmentOverlay(el);
                }
                pruneHiddenByUs();
            }
            setInterval(suppressAutoPreviews, 400);
            var suppressTimer = null;
            var suppressObserver = new MutationObserver(function() {
                if (suppressTimer) return;
                suppressTimer = setTimeout(function() {
                    suppressTimer = null;
                    suppressAutoPreviews();
                }, 250);
            });
            if (document.body) {
                suppressObserver.observe(document.body, { childList: true, subtree: true });
            }

            function reportAttachments() {
                try { ZaiUiBridge.reportAttachments(attachmentOverlays().length); } catch(e) {}
            }
            setInterval(reportAttachments, 700);

            window.__zaiHideAttachments = function() {
                var list = attachmentOverlays();
                var closed = 0;
                intentUntil = 0;
                allowedOverlays = (typeof WeakSet !== 'undefined') ? new WeakSet() : null;
                for (var i = 0; i < list.length; i++) {
                    closeAttachmentOverlay(list[i]);
                    closed++;
                }
                try { ZaiUiBridge.reportAttachments(0); } catch(e) {}
                return closed;
            };
        })();
    """.trimIndent()

    /**
     * Перехват blob:URL. chat.z.ai отдаёт скачиваемые файлы через blob и
     * часто отзывает ссылку сразу после клика. Старый код делал fetch()
     * уже после revoke — отсюда «TypeError: Failed to fetch». Теперь держим
     * собственную ссылку на Blob и читаем её напрямую.
     */
    private val blobHookJs = """
        (function() {
            if (window.__zaiBlobHooksInstalled) return;
            window.__zaiBlobHooksInstalled = true;
            window.__zaiBlobs = {};
            // Оригинальные имена файлов из атрибута download. Сайт отдаёт
            // вложения как blob:URL, поэтому DownloadManager их не видит,
            // а без этого списка приходилось придумывать zai-file-<дата>.
            window.__zaiBlobNames = {};
            function rememberDownloadName(a) {
                try {
                    if (a && a.href && a.download) {
                        window.__zaiBlobNames[a.href] = a.download;
                    }
                } catch (e) {}
            }
            document.addEventListener('click', function(ev) {
                var a = ev.target && ev.target.closest
                    ? ev.target.closest('a[download]')
                    : null;
                rememberDownloadName(a);
            }, true);
            try {
                var origAnchorClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                    rememberDownloadName(this);
                    return origAnchorClick.apply(this, arguments);
                };
            } catch (e) {}
            try {
                var origCreate = URL.createObjectURL;
                URL.createObjectURL = function(obj) {
                    var u = origCreate.call(URL, obj);
                    try { window.__zaiBlobs[u] = obj; } catch (e) {}
                    return u;
                };
                var origRevoke = URL.revokeObjectURL;
                URL.revokeObjectURL = function(u) {
                    try {
                        setTimeout(function() {
                            try { delete window.__zaiBlobs[u]; } catch (e) {}
                        }, 300000);
                    } catch (e) {}
                    try { return origRevoke.call(URL, u); } catch (e) {}
                };
            } catch (e) {}
            window.__zaiSaveBlob = function(url, name) {
                var b = window.__zaiBlobs[url];
                if (!b) return false;
                var realName = (window.__zaiBlobNames && window.__zaiBlobNames[url]) || name;
                realName = String(realName).split(/[\\/]/).pop() || name;
                try {
                    var fr = new FileReader();
                    fr.onload = function() {
                        try {
                            ZaiBlobSaver.save(
                                realName,
                                String(fr.result).split(',')[1],
                                b.type || 'application/octet-stream'
                            );
                        } catch (e) { ZaiBlobSaver.fail(String(e)); }
                    };
                    fr.onerror = function() { ZaiBlobSaver.fail('FileReader error'); };
                    fr.readAsDataURL(b);
                    return true;
                } catch (e) { return false; }
            };
        })();
    """.trimIndent()

    /**
     * Автовыбор последней модели. Выполняется НЕ БОЛЕЕ ОДНОГО РАЗА на запуск
     * приложения: старый вариант перезапускался на каждом onPageFinished и
     * тремя таймерами (1200/3000/5000 мс) заново открывал меню — пользователь
     * видел выбор модели трижды. Теперь одна попытка и флаг done сразу после
     * клика по триггеру; если список не нашёлся, меню закрывается обратно.
     */
    private val modelAutoSelectJs = """
        (function() {
            if (window.__zaiModelPickerDone) return;
            window.__zaiModelPickerDone = true;
            var AUTO_SELECT_MODEL = $autoSelectLatestModel;
            if (!AUTO_SELECT_MODEL) return;
            function parseModelVersion(text) {
                var m = text.match(/glm[\s._-]*(\d+)(?:[._-](\d+))?/i);
                if (!m) return null;
                return [parseInt(m[1], 10), m[2] ? parseInt(m[2], 10) : -1];
            }
            function isVisibleEl(el) {
                var r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 &&
                    window.getComputedStyle(el).visibility !== 'hidden';
            }
            function looksClickable(el) {
                return !!el.closest('button, a, [role="option"], [role="menuitem"], [role="menuitemradio"], [class*="item"], [class*="option"], [class*="trigger"]');
            }
            function modelCandidates() {
                var out = [];
                var all = document.querySelectorAll('button, a, li, span, div, [role="option"], [role="menuitem"]');
                for (var i = 0; i < all.length && i < 4000; i++) {
                    var el = all[i];
                    if (!isVisibleEl(el)) continue;
                    var t = (el.textContent || '').trim();
                    if (t.length > 30) continue;
                    var v = parseModelVersion(t);
                    if (!v) continue;
                    var r = el.getBoundingClientRect();
                    out.push({ el: el, text: t, ver: v, area: r.width * r.height });
                }
                return out;
            }
            function bestModelFrom(list) {
                var best = null;
                for (var i = 0; i < list.length; i++) {
                    var c = list[i];
                    if (!looksClickable(c.el)) continue;
                    if (!best || c.ver[0] > best.ver[0] ||
                        (c.ver[0] === best.ver[0] && c.ver[1] > best.ver[1])) best = c;
                }
                return best;
            }
            function run() {
                if (!/^https?:\/\/([^/]*\.)?z\.ai/.test(location.href)) return;
                if (document.querySelector('[role="dialog"]:not([hidden])')) return;
                if (!document.querySelector('textarea, [contenteditable="true"]')) return;
                var cands = modelCandidates();
                if (cands.length === 0) return;
                var byText = {};
                for (var i = 0; i < cands.length; i++) {
                    var key = cands[i].text.toLowerCase().replace(/[\s._-]+/g, '');
                    if (!byText[key] || cands[i].area < byText[key].area) byText[key] = cands[i];
                }
                var uniq = [];
                for (var k in byText) uniq.push(byText[k]);
                if (uniq.length === 1) {
                    var trigger = uniq[0];
                    if (!looksClickable(trigger.el)) return;
                    var opened = false;
                    try { trigger.el.click(); opened = true; } catch (e) {}
                    if (!opened) return;
                    setTimeout(function() {
                        var best = bestModelFrom(modelCandidates());
                        if (best) {
                            try { best.el.click(); } catch (e) {}
                        } else {
                            // Список не распознан — возвращаем меню в исходное
                            // состояние, чтобы оно не висело открытым.
                            try { trigger.el.click(); } catch (e) {}
                        }
                    }, 600);
                } else {
                    var smallest = uniq[0];
                    for (var j = 1; j < uniq.length; j++) {
                        if (uniq[j].area < smallest.area) smallest = uniq[j];
                    }
                    var bestOpen = bestModelFrom(uniq);
                    if (!bestOpen) return;
                    if (bestOpen.text.toLowerCase() === smallest.text.toLowerCase()) return;
                    try { bestOpen.el.click(); } catch (e) {}
                }
            }
            setTimeout(run, 2200);
        })();
    """.trimIndent()

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (filePathCallback != null) {
            val baseIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            openFilePicker(baseIntent, includeCamera = granted)
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results: Array<Uri>? = when {
            result.resultCode != Activity.RESULT_OK -> null
            result.data?.data != null -> arrayOf(result.data!!.data!!)
            result.data?.clipData != null -> {
                val clip = result.data!!.clipData!!
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            }
            cameraPhotoUri != null -> arrayOf(cameraPhotoUri!!)
            else -> null
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
        cameraPhotoUri = null
    }

    private val googleAccountLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val url = pendingGoogleAuthUrl
        pendingGoogleAuthUrl = null
        if (url == null) return@registerForActivityResult

        val accountName = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        } else {
            null
        }
        val target = pendingGoogleTarget
        pendingGoogleTarget = null
        val safeTarget = when {
            target === popupWebView -> popupWebView ?: webView
            target === webView -> webView
            else -> webView
        }
        safeTarget.loadUrl(if (accountName != null) withLoginHint(url, accountName) else url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Edge-to-edge AMOLED immersive chrome
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        fabHome = findViewById(R.id.fabHome)

        // Cinematic FAB reveal
        fabHome.visibility = View.INVISIBLE
        Handler(Looper.getMainLooper()).postDelayed({
            fabHome.visibility = View.VISIBLE
            fabHome.startAnimation(
                AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
            )
        }, 800)

        setupSwipeRefresh()
        setupWebView()
        setupBackNavigation()
        setupFAB()

        if (savedInstanceState == null) {
            webView.loadUrl(targetUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.crimson_red),
            ContextCompat.getColor(this, R.color.neon_yellow),
            ContextCompat.getColor(this, R.color.emerald_green),
            ContextCompat.getColor(this, R.color.deep_blue)
        )
        swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.amoled_black)
        )
        swipeRefresh.setOnRefreshListener { webView.reload() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // === Google SSO: разрешаем всплывающие окна ===
            // OAuth flow Google (Sign in with Google) открывает accounts.google.com
            // через window.open(). Без этих двух строк WebView молча игнорирует
            // вызов, и сайт вообще не может начать процесс входа.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true

            // === Google SSO: Chrome-подобный User-Agent ===
            // Без подмены UA Google видит "не настоящий браузер" и кидает на
            // веб-форму ввода email/пароля. С подменой — открывает нативный flow
            // с системным селектором аккаунтов Android.
            userAgentString = chromeLikeUserAgent
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.setBackgroundColor(Color.BLACK)
        webView.isScrollbarFadingEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }
        webView.settings.saveFormData = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // === JS-мост для умного pull-to-refresh ===
        // Внутренний JS (hideChromeJs) вызывает ZaiScrollBridge.reportScroll(top)
        // при каждом скролле — ZaiSwipeRefresh по этому значению решает,
        // разрешать ли pull-to-refresh.
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun reportScroll(top: Int) {
                runOnUiThread {
                    if (::swipeRefresh.isInitialized) {
                        swipeRefresh.innerScrollTop = top
                    }
                }
            }
        }, "ZaiScrollBridge")

        // === Состояние оверлеев-вложений для аппаратной кнопки «Назад» ===
        // JS раз в 700 мс присылает число открытых превью/галерей. По нему
        // back сначала скрывает вложения, а не уводит из чата.
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun reportAttachments(count: Int) {
                runOnUiThread { attachmentOverlayCount = count }
            }
        }, "ZaiUiBridge")

        // === JS-мост для сохранения файлов, приходящих как blob: ===
        // DownloadManager не умеет качать blob:URL — данные существуют только
        // в памяти страницы. JS fetch'ает blob, конвертирует в base64 и отдаёт
        // сюда; Kotlin сохраняет байты в системную папку Download.
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun save(name: String, base64: String, mime: String) {
                val bytes = try {
                    Base64.decode(base64, Base64.DEFAULT)
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (bytes == null || bytes.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Download failed: empty file data",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return
                }
                val mimeType = mime.ifBlank { "application/octet-stream" }
                // Имя было сгенерировано без знания реального типа — чиним
                // расширение .bin по фактическому MIME из blob.
                val fixedName = if (name.endsWith(".bin")) {
                    name.removeSuffix(".bin") + "." + guessExtension(mimeType)
                } else {
                    name
                }
                runOnUiThread {
                    saveBytesToDownloads(sanitizeFileName(fixedName), mimeType, bytes)
                }
            }

            @JavascriptInterface
            fun fail(message: String) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Download failed: $message",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }, "ZaiBlobSaver")

        // === Проблема №8: все загрузки — в системную папку «Загрузки» (Download) ===
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (!url.isNullOrBlank()) {
                handleDownload(url, userAgent ?: "", contentDisposition, mimetype)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                return when {
                    // Только z.ai грузим в WebView. Всё остальное — во внешний браузер,
                    // чтобы пользователь не застрял на китайской версии (chatglm.cn,
                    // bigmodel.cn, zhipuai.cn и т.п.) и мог вернуться кнопкой "Домой".
                    (isZaiUrl(url) || isGoogleAuthUrl(url)) &&
                        (url.startsWith("http://") || url.startsWith("https://")) -> {
                        if (isGoogleAuthUrl(url) &&
                            request.isForMainFrame &&
                            !url.contains("login_hint=")
                        ) {
                            showGoogleAccountChooser(url, view)
                            true
                        } else {
                            view.loadUrl(url)
                            false
                        }
                    }
                    url.startsWith("http://") || url.startsWith("https://") -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(
                                this@MainActivity,
                                "No browser app found to open this link",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        true
                    }
                    else -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(
                                this@MainActivity,
                                "No app found to open this link",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        true
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // Новая страница — внутренний скролл начался с нуля.
                swipeRefresh.innerScrollTop = 0
                view.evaluateJavascript(blobHookJs, null)
                view.evaluateJavascript(hideChromeJs, null)
                if (!modelAutoSelectInjected) {
                    modelAutoSelectInjected = true
                    view.evaluateJavascript(modelAutoSelectJs, null)
                }
                swipeRefresh.isRefreshing = false
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                swipeRefresh.isRefreshing = false
                if (request.isForMainFrame) {
                    Toast.makeText(
                        this@MainActivity,
                        "Connection error — pull down to retry",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            /**
             * === Google SSO: обработка window.open для OAuth ===
             *
             * Когда сайт вызывает window.open(url, ...), WebView просит нас
             * создать и вернуть ему новую "вьюху", в которой будет грузиться url.
             * Мы создаём второй WebView, шарим с ним CookieManager (чтобы сессия
             * после входа вернулась в основной), добавляем в контейнер и
             * возвращаем через result.
             *
             * Когда popup закрывается (window.close или мы сами решили) —
             * убираем второй WebView, а cookie-flush гарантирует, что основной
             * WebView "увидит" новую сессию.
             */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val container = findViewById<FrameLayout>(android.R.id.content) ?: return false

                closePopup()

                val popup = WebView(this@MainActivity).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        userAgentString = chromeLikeUserAgent
                        setSupportMultipleWindows(true)
                        javaScriptCanOpenWindowsAutomatically = true
                    }
                    setBackgroundColor(Color.BLACK)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            v: WebView,
                            req: WebResourceRequest
                        ): Boolean {
                            val url = req.url.toString()
                            if (isZaiUrl(url)) {
                                webView.post {
                                    closePopup()
                                    CookieManager.getInstance().flush()
                                    webView.reload()
                                }
                                return true
                            }
                            if (isGoogleAuthUrl(url) && !url.contains("login_hint=")) {
                                showGoogleAccountChooser(url, v)
                                return true
                            }
                            return false
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView?) {
                            webView.post { closePopup() }
                        }
                    }
                }

                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)

                val params = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                container.addView(popup, params)

                popupContainer = container
                popupWebView = popup

                transport.webView = popup
                resultMsg?.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val baseIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                val wantsCamera = params.acceptTypes.isEmpty() ||
                    params.acceptTypes.any {
                        it.contains("image") || it.equals("*/*", true)
                    }
                if (wantsCamera &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        android.Manifest.permission.CAMERA
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                    return true
                }
                openFilePicker(baseIntent, includeCamera = wantsCamera)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val needed = request.resources
                    val needsCamera = needed.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (needsCamera &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            android.Manifest.permission.CAMERA
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                        request.deny()
                    } else {
                        request.grant(needed)
                    }
                }
            }
        }
    }

    private fun openFilePicker(baseIntent: Intent, includeCamera: Boolean) {
        val chooserIntent = Intent.createChooser(baseIntent, "Select file or capture photo")
        if (includeCamera) {
            val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (captureIntent.resolveActivity(packageManager) != null) {
                try {
                    val photoFile = createImageFile()
                    cameraPhotoUri = FileProvider.getUriForFile(
                        this,
                        "${packageName}.fileprovider",
                        photoFile
                    )
                    captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                    captureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(captureIntent))
                } catch (_: IOException) {
                    // Camera unavailable — fall back to file picker only
                }
            }
        }
        try {
            fileChooserLauncher.launch(chooserIntent)
        } catch (_: ActivityNotFoundException) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            Toast.makeText(this, "No file chooser available", Toast.LENGTH_SHORT).show()
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = cacheDir
        return File.createTempFile("IMG_${timeStamp}_", ".jpg", storageDir)
    }

    // ==================================================================
    // === Проблема №8: загрузка файлов в папку «Загрузки» (Download) ===
    // ==================================================================

    private var pendingLegacySave: Triple<String, String, ByteArray>? = null

    // На Android 9 и ниже (API 28-) запись в общую папку Download требует
    // runtime-разрешения WRITE_EXTERNAL_STORAGE. На Android 10+ MediaStore
    // работает без разрешений — лончер не используется.
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingLegacySave
        pendingLegacySave = null
        if (granted && pending != null) {
            writeLegacyDownload(pending.first, pending.second, pending.third)
        } else {
            Toast.makeText(
                this,
                "Storage permission denied — file not saved",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Диспетчер загрузок: http → DownloadManager, blob → JS-мост,
     *  data: → прямой парсинг, остальное → внешнее приложение. */
    private fun handleDownload(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimetype: String?
    ) {
        try {
            when {
                url.startsWith("blob:") -> saveBlobViaJs(url, contentDisposition, mimetype)
                url.startsWith("data:") -> saveDataUri(url)
                url.startsWith("http://") || url.startsWith("https://") ->
                    enqueueHttpDownload(url, userAgent, contentDisposition, mimetype)
                else -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Обычные http(s)-ссылки качает системный DownloadManager — файл
     *  появляется в папке Download («Загрузки»), прогресс и завершение видны
     *  в шторке уведомлений. Cookie и User-Agent передаём обязательно —
     *  без них сервер отдаёт ошибку авторизации вместо файла. */
    private fun enqueueHttpDownload(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimetype: String?
    ) {
        val fileName = resolveFileName(url, contentDisposition, mimetype)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            addRequestHeader("User-Agent", userAgent.ifBlank { chromeLikeUserAgent })
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            if (!mimetype.isNullOrBlank()) setMimeType(mimetype)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(this, "Downloading to «Download»: $fileName", Toast.LENGTH_LONG).show()
    }

    /** blob:URL существует только в памяти страницы — DownloadManager его
     *  скачать не может. Просим страницу fetch'нуть blob и отдать base64
     *  через мост ZaiBlobSaver.save(). */
    private fun saveBlobViaJs(blobUrl: String, contentDisposition: String?, mimetype: String?) {
        val suggestedName = resolveFileName(blobUrl, contentDisposition, mimetype)
        // Сначала берём Blob из собственного хранилища (blobHookJs): сайт часто
        // отзывает blob:URL сразу после клика, и fetch() падает с «Failed to fetch».
        val js = """
            (function() {
                var url = ${JSONObject.quote(blobUrl)};
                var name = ${JSONObject.quote(suggestedName)};
                if (window.__zaiSaveBlob && window.__zaiSaveBlob(url, name)) return 'ok';
                return 'fallback';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            // evaluateJavascript отдаёт JSON-строку, например "\"ok\"".
            if (result != null && result.contains("ok")) {
                Toast.makeText(this, "Preparing download…", Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            // Blob создан до установки хука или хранилище недоступно — пробуем
            // обычный fetch с более информативной ошибкой.
            val fallbackJs = """
                (function() {
                    var url = ${JSONObject.quote(blobUrl)};
                    var name = ${JSONObject.quote(suggestedName)};
                    fetch(url, { credentials: 'include' })
                        .then(function(r) {
                            if (!r.ok) throw new Error('HTTP ' + r.status);
                            return r.blob();
                        })
                        .then(function(b) {
                            var fr = new FileReader();
                            fr.onload = function() {
                                try {
                                    ZaiBlobSaver.save(
                                        name,
                                        String(fr.result).split(',')[1],
                                        b.type || 'application/octet-stream'
                                    );
                                } catch (e) { ZaiBlobSaver.fail(String(e)); }
                            };
                            fr.onerror = function() { ZaiBlobSaver.fail('FileReader error'); };
                            fr.readAsDataURL(b);
                        })
                        .catch(function(e) { ZaiBlobSaver.fail(String(e)); });
                })();
            """.trimIndent()
            webView.evaluateJavascript(fallbackJs, null)
        }
        Toast.makeText(this, "Preparing download…", Toast.LENGTH_SHORT).show()
    }

    /** data:URL — парсим и сохраняем напрямую. */
    private fun saveDataUri(url: String) {
        val commaIndex = url.indexOf(',')
        if (commaIndex <= 0) {
            Toast.makeText(this, "Download failed: malformed data URI", Toast.LENGTH_LONG).show()
            return
        }
        val header = url.substring(5, commaIndex) // "mime;base64" или "mime"
        val mime = header.substringBefore(';').ifBlank { "application/octet-stream" }
        val payload = url.substring(commaIndex + 1)
        val bytes = if (header.endsWith(";base64")) {
            Base64.decode(payload, Base64.DEFAULT)
        } else {
            URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
        }
        val name = "zai-file-${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.${guessExtension(mime)}"
        saveBytesToDownloads(sanitizeFileName(name), mime, bytes)
    }

    /** Запись байтов в системную папку Download.
     *  Android 10+ — MediaStore.Downloads (без разрешений);
     *  Android 9 и ниже — публичная папка после запроса WRITE_EXTERNAL_STORAGE. */
    private fun saveBytesToDownloads(fileName: String, mime: String, bytes: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    Toast.makeText(
                        this,
                        "Download failed: cannot create file entry",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IOException("Cannot open output stream")
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                Toast.makeText(this, "Saved to «Download»: $fileName", Toast.LENGTH_LONG).show()
            } else {
                if (ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingLegacySave = Triple(fileName, mime, bytes)
                    storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    return
                }
                writeLegacyDownload(fileName, mime, bytes)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun writeLegacyDownload(fileName: String, mime: String, bytes: ByteArray) {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        var target = File(dir, fileName)
        var n = 1
        while (target.exists()) {
            val dot = fileName.lastIndexOf('.')
            target = if (dot > 0) {
                File(dir, fileName.substring(0, dot) + " ($n)" + fileName.substring(dot))
            } else {
                File(dir, "$fileName ($n)")
            }
            n++
        }
        FileOutputStream(target).use { it.write(bytes) }
        MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), arrayOf(mime), null)
        Toast.makeText(this, "Saved to «Download»: ${target.name}", Toast.LENGTH_LONG).show()
    }

    /** Имя файла: contentDisposition → последний сегмент URL → метка времени. */
    private fun resolveFileName(url: String, contentDisposition: String?, mimetype: String?): String {
        if (!contentDisposition.isNullOrBlank()) {
            // RFC 5987: filename*=UTF-8''name.ext
            val utf8 = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
                .find(contentDisposition)?.groupValues?.get(1)
            if (!utf8.isNullOrBlank()) {
                return URLDecoder.decode(utf8, "UTF-8")
            }
            val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(contentDisposition)?.groupValues?.get(1)
            if (!plain.isNullOrBlank()) return plain.trim()
        }
        if (url.startsWith("http")) {
            val path = Uri.parse(url).lastPathSegment
            if (!path.isNullOrBlank() && path.contains('.') && !path.endsWith("/")) {
                return URLDecoder.decode(path, "UTF-8")
            }
        }
        val mime = mimetype?.takeIf { it.isNotBlank() && it != "/" } ?: "application/octet-stream"
        return "zai-file-${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.${guessExtension(mime)}"
    }

    private fun guessExtension(mime: String): String = when {
        mime.contains("pdf") -> "pdf"
        mime.contains("png") -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("gif") -> "gif"
        mime.contains("webp") -> "webp"
        mime.contains("svg") -> "svg"
        mime.contains("csv") -> "csv"
        mime.contains("html") -> "html"
        mime.contains("json") -> "json"
        mime.contains("zip") -> "zip"
        mime.contains("wav") -> "wav"
        mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
        mime.contains("mp4") || mime.contains("video") -> "mp4"
        mime.contains("text") -> "txt"
        mime.contains("word") || mime.contains("officedocument") -> "docx"
        else -> "bin"
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim()
        val safe = if (cleaned.isBlank()) "zai-file" else cleaned.take(120)
        return if (safe.contains('.')) safe else "$safe.bin"
    }

    private fun isZaiUrl(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase(Locale.US) ?: return false
        return host == "z.ai" || host.endsWith(".z.ai")
    }

    private fun isGoogleAuthUrl(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase(Locale.US) ?: return false
        return host.startsWith("accounts.google.") ||
            host == "accounts.youtube.com" ||
            host == "accounts.googleusercontent.com" ||
            host == "oauth2.googleapis.com"
    }

    private fun withLoginHint(url: String, email: String): String {
        if (url.contains("login_hint=")) return url
        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}login_hint=${Uri.encode(email)}"
    }

    private fun showGoogleAccountChooser(url: String, target: WebView) {
        pendingGoogleAuthUrl = url
        pendingGoogleTarget = target
        val intent = AccountManager.newChooseAccountIntent(
            null,
            null,
            arrayOf("com.google"),
            false,
            null,
            null,
            null,
            null
        )
        try {
            googleAccountLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            pendingGoogleAuthUrl = null
            pendingGoogleTarget = null
            target.loadUrl(url)
        }
    }

    private fun closePopup() {
        val popup = popupWebView ?: return
        popupContainer?.removeView(popup)
        popup.stopLoading()
        popup.removeAllViews()
        popup.destroy()
        popupWebView = null
        popupContainer = null
    }

    private fun setupFAB() {
        fabHome.setOnClickListener {
            // FAB ВСЕГДА возвращает на главный URL chat.z.ai — независимо от
            // canGoBack(). Это гарантирует: запутались на китайском сайте или
            // зашли в глубь истории — тап по FAB возвращает на главную.
            // Если уже на главном — просто перезагружаем (чтобы не было
            // "мертвого" тапа).
            val currentUrl = webView.url?.trimEnd('/')
            if (currentUrl == targetUrl.trimEnd('/')) {
                webView.reload()
            } else {
                webView.loadUrl(targetUrl)
            }
            fabHome.startAnimation(
                AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
            )
        }
        fabHome.setOnLongClickListener {
            webView.reload()
            Toast.makeText(this, "Reloading…", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (popupWebView != null) {
                    closePopup()
                    return
                }
                // Если поверх чата открыты превью/галереи вложений — «Назад»
                // скрывает их все и остаётся в чате (а не листает историю).
                if (attachmentOverlayCount > 0) {
                    attachmentOverlayCount = 0
                    webView.evaluateJavascript(
                        "(function(){try{return window.__zaiHideAttachments?" +
                            "window.__zaiHideAttachments():0;}catch(e){return 0;}})();",
                        null
                    )
                    return
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    showExitPrompt()
                }
            }
        })
    }

    private fun showExitPrompt() {
        AlertDialog.Builder(
            this,
            com.google.android.material.R.style.MaterialAlertDialog_Material3
        )
            .setTitle("Exit Z chat")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Exit") { _, _ -> finishAffinity() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        popupWebView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        popupWebView?.onResume()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        closePopup()
        webView.apply {
            stopLoading()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }
}
