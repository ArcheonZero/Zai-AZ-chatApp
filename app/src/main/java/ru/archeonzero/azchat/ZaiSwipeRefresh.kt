package ru.archeonzero.azchat

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * SwipeRefreshLayout, который понимает внутренний скролл SPA-страницы.
 *
 * Проблема: чат z.ai прокручивается ВНУТРИ внутреннего div (SPA-структура),
 * а не самим WebView. Стандартный SwipeRefreshLayout проверяет только
 * WebView.scrollY, который всегда 0, и поэтому перехватывает ЛЮБОЙ свайп
 * вниз как pull-to-refresh — даже когда пользователь просто скроллит
 * историю сообщений вверх.
 *
 * Решение: MainActivity через JS-мост (ZaiScrollBridge.reportScroll) сообщает
 * сюда позицию скролла внутреннего контейнера. canChildScrollUp() возвращает
 * true (refresh запрещён), если внутренний контейнер прокручен хотя бы на 5px.
 * Refresh срабатывает только когда страница реально на самом верху.
 */
class ZaiSwipeRefresh @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    /**
     * Позиция вертикального скролла самого прокрученного внутреннего контейнера.
     * Обновляется из MainActivity через JavascriptInterface (не с UI-потока,
     * поэтому запись поля — без блокировок; для int это допустимо).
     */
    @Volatile
    var innerScrollTop: Int = 0

    override fun canChildScrollUp(): Boolean {
        // 1) Если сам WebView прокручен — refresh запрещён (штатная логика).
        val child = getChildAt(0)
        if (child is WebView && child.scrollY > 0) return true

        // 2) Если внутренний контейнер чата прокручен — refresh тоже запрещён.
        //    Порог 5px отсекает случайные субпиксельные смещения.
        return innerScrollTop > 5
    }
}
