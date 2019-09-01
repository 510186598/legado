package io.legado.app.ui.readbook

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
import androidx.core.view.isVisible
import androidx.lifecycle.Observer
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.Bus
import io.legado.app.constant.Status
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.IntentDataHelp
import io.legado.app.help.ReadBookConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.noButton
import io.legado.app.lib.dialogs.okButton
import io.legado.app.receiver.TimeElectricityReceiver
import io.legado.app.service.ReadAloudService
import io.legado.app.ui.changesource.ChangeSourceDialog
import io.legado.app.ui.chapterlist.ChapterListActivity
import io.legado.app.ui.readbook.config.*
import io.legado.app.ui.readbook.config.BgTextConfigDialog.Companion.BG_COLOR
import io.legado.app.ui.readbook.config.BgTextConfigDialog.Companion.TEXT_COLOR
import io.legado.app.ui.replacerule.ReplaceRuleActivity
import io.legado.app.ui.sourceedit.SourceEditActivity
import io.legado.app.ui.widget.page.ChapterProvider
import io.legado.app.ui.widget.page.PageView
import io.legado.app.ui.widget.page.TextChapter
import io.legado.app.ui.widget.page.delegate.PageDelegate
import io.legado.app.utils.*
import kotlinx.android.synthetic.main.activity_read_book.*
import kotlinx.android.synthetic.main.view_book_page.*
import kotlinx.android.synthetic.main.view_read_menu.*
import kotlinx.android.synthetic.main.view_title_bar.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.internal.toHexString
import org.jetbrains.anko.sdk27.listeners.onClick
import org.jetbrains.anko.startActivity
import org.jetbrains.anko.startActivityForResult
import org.jetbrains.anko.toast

class ReadBookActivity : VMBaseActivity<ReadBookViewModel>(R.layout.activity_read_book),
    PageView.CallBack,
    ChangeSourceDialog.CallBack,
    ReadBookViewModel.CallBack,
    ColorPickerDialogListener {
    override val viewModel: ReadBookViewModel
        get() = getViewModel(ReadBookViewModel::class.java)

    private val requestCodeEditSource = 111
    private var changeSourceDialog: ChangeSourceDialog? = null
    private var timeElectricityReceiver: TimeElectricityReceiver? = null
    var readAloudStatus = Status.STOP

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setSupportActionBar(toolbar)
        initView()
        page_view.callback = this
        viewModel.callBack = this
        viewModel.bookData.observe(this, Observer { title_bar.title = it.name })
        viewModel.chapterListFinish.observe(this, Observer { loadContent() })
        viewModel.initData(intent)
        savedInstanceState?.let {
            changeSourceDialog =
                supportFragmentManager.findFragmentByTag(ChangeSourceDialog.tag) as? ChangeSourceDialog
        }
        setScreenBrightness(getPrefInt("brightness", 100))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Help.upSystemUiVisibility(window, !read_menu.isVisible)
    }

    override fun onResume() {
        super.onResume()
        Help.upSystemUiVisibility(window, !read_menu.isVisible)
        timeElectricityReceiver = TimeElectricityReceiver.register(this)
    }

    override fun onPause() {
        super.onPause()
        timeElectricityReceiver?.let {
            unregisterReceiver(it)
            timeElectricityReceiver = null
        }
        Help.upSystemUiVisibility(window, !read_menu.isVisible)
    }

    /**
     * 初始化View
     */
    private fun initView() {
        tv_chapter_name.onClick {
            viewModel.webBook?.let {
                startActivityForResult<SourceEditActivity>(
                    requestCodeEditSource,
                    Pair("data", it.bookSource.bookSourceUrl)
                )
            }
        }
        tv_chapter_url.onClick {
            runCatching {
                val url = tv_chapter_url.text.toString()
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse(url)
                startActivity(intent)
            }
        }
        read_menu.setListener(object : ReadMenu.Callback {
            override fun setScreenBrightness(value: Int) {
                this@ReadBookActivity.setScreenBrightness(value)
            }

            override fun autoPage() {

            }

            override fun skipToPage(page: Int) {
                viewModel.durPageIndex = page
                page_view.upContent()
                curPageChanged()
            }

            override fun skipPreChapter() {
                moveToPrevChapter(false)
                page_view.upContent()
            }

            override fun skipNextChapter() {
                moveToNextChapter()
                page_view.upContent()
            }

            override fun openReplaceRule() {
                startActivity<ReplaceRuleActivity>()
            }

            override fun openChapterList() {
                viewModel.bookData.value?.let {
                    startActivity<ChapterListActivity>(Pair("bookUrl", it.bookUrl))
                }
            }

            override fun showReadStyle() {
                ReadStyleDialog().show(supportFragmentManager, "readStyle")
            }

            override fun showMoreSetting() {
                MoreConfigDialog().show(supportFragmentManager, "moreConfig")
            }

            override fun menuShow() {
                Help.upSystemUiVisibility(window, !read_menu.isVisible)
            }

            override fun menuHide() {
                Help.upSystemUiVisibility(window, !read_menu.isVisible)
            }
        })
    }

    fun showPaddingConfig() {
        PaddingConfigDialog().show(supportFragmentManager, "paddingConfig")
    }

    fun showBgTextConfig() {
        BgTextConfigDialog().show(supportFragmentManager, "bgTextConfig")
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.read_book, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    /**
     * 菜单
     */
    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_change_source -> {
                read_menu.runMenuOut()
                if (changeSourceDialog == null) {
                    viewModel.bookData.value?.let {
                        changeSourceDialog = ChangeSourceDialog
                            .newInstance(it.name, it.author)
                    }
                }
                changeSourceDialog?.show(supportFragmentManager, ChangeSourceDialog.tag)
            }
            R.id.menu_refresh -> {
                viewModel.bookData.value?.let {
                    viewModel.curTextChapter = null
                    page_view.upContent()
                    viewModel.refreshContent(it)
                }
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /**
     * 按键拦截,显示菜单
     */
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        val keyCode = event?.keyCode
        val action = event?.action
        val isDown = action == 0

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (isDown && !read_menu.cnaShowMenu) {
                read_menu.runMenuIn()
                return true
            }
            if (!isDown && !read_menu.cnaShowMenu) {
                read_menu.cnaShowMenu = true
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (readAloudStatus == Status.PLAY) {
                    ReadAloudService.pause(this)
                    toast(R.string.read_aloud_pause)
                    return true
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (volumeKeyPage(PageDelegate.Direction.PREV)) {
                    return true
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (volumeKeyPage(PageDelegate.Direction.NEXT)) {
                    return true
                }
            }
            KeyEvent.KEYCODE_SPACE -> {

            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (volumeKeyPage(PageDelegate.Direction.NONE)) {
                    return true
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun volumeKeyPage(direction: PageDelegate.Direction): Boolean {
        if (!read_menu.isVisible) {
            if (getPrefBoolean("volumeKeyPage", true)) {
                if (getPrefBoolean("volumeKeyPageOnPlay")
                    || readAloudStatus != Status.PLAY
                ) {
                    when (direction) {
                        PageDelegate.Direction.PREV -> {

                        }
                        PageDelegate.Direction.NEXT -> {

                        }
                    }
                    return true
                }
            }
        }
        return false
    }

    /**
     * 加载章节内容
     */
    private fun loadContent() {
        viewModel.bookData.value?.let {
            viewModel.loadContent(it, viewModel.durChapterIndex)
            viewModel.loadContent(it, viewModel.durChapterIndex + 1)
            viewModel.loadContent(it, viewModel.durChapterIndex - 1)
        }
    }

    /**
     * 加载章节内容
     */
    override fun loadContent(index: Int) {
        viewModel.bookData.value?.let {
            viewModel.loadContent(it, index)
        }
    }

    /**
     * 内容加载完成
     */
    override fun contentLoadFinish(bookChapter: BookChapter, content: String) {
        when (bookChapter.index) {
            viewModel.durChapterIndex -> launch {
                viewModel.curTextChapter = ChapterProvider
                    .getTextChapter(content_text_view, bookChapter, content, viewModel.chapterSize)
                page_view.upContent()
                curChapterChanged()
            }
            viewModel.durChapterIndex - 1 -> launch {
                viewModel.prevTextChapter = ChapterProvider
                    .getTextChapter(content_text_view, bookChapter, content, viewModel.chapterSize)
                page_view.upContent()
            }
            viewModel.durChapterIndex + 1 -> launch {
                viewModel.nextTextChapter = ChapterProvider
                    .getTextChapter(content_text_view, bookChapter, content, viewModel.chapterSize)
                page_view.upContent()
            }
        }
    }

    override fun upContent() {
        page_view.upContent()
    }

    private fun curChapterChanged() {
        viewModel.curTextChapter?.let {
            tv_chapter_name.text = it.title
            tv_chapter_name.visible()
            if (!viewModel.isLocalBook) {
                tv_chapter_url.text = it.url
                tv_chapter_url.visible()
            }
            read_menu.upReadProgress(it.pageSize().minus(1), viewModel.durPageIndex)
            curPageChanged()
            tv_pre.isEnabled = viewModel.durChapterIndex != 0
            tv_next.isEnabled = viewModel.durChapterIndex != viewModel.chapterSize - 1
        }
    }

    private fun curPageChanged() {
        when (readAloudStatus) {
            Status.PLAY -> readAloud()
            Status.PAUSE -> {
                readAloud(false)
            }
        }
    }

    override fun chapterSize(): Int {
        return viewModel.chapterSize
    }

    override fun curOrigin(): String? {
        return viewModel.bookData.value?.origin
    }

    override fun oldBook(): Book? {
        return viewModel.bookData.value
    }

    override fun changeTo(book: Book) {
        viewModel.changeTo(book)
    }

    override fun durChapterIndex(): Int {
        return viewModel.durChapterIndex
    }

    override fun durChapterPos(): Int {
        viewModel.curTextChapter?.let {
            if (viewModel.durPageIndex < it.pageSize()) {
                return viewModel.durPageIndex
            }
            return it.pageSize() - 1
        }
        return viewModel.durPageIndex
    }

    override fun setPageIndex(pageIndex: Int) {
        viewModel.durPageIndex = pageIndex
        viewModel.saveRead()
        curPageChanged()
    }

    override fun textChapter(chapterOnDur: Int): TextChapter? {
        return when (chapterOnDur) {
            0 -> viewModel.curTextChapter
            1 -> viewModel.nextTextChapter
            -1 -> viewModel.prevTextChapter
            else -> null
        }
    }

    override fun moveToNextChapter() {
        viewModel.durPageIndex = 0
        viewModel.moveToNextChapter()
        viewModel.saveRead()
        curChapterChanged()
    }

    override fun moveToPrevChapter(last: Boolean) {
        viewModel.durPageIndex = if (last) viewModel.prevTextChapter?.lastIndex() ?: 0 else 0
        viewModel.moveToPrevChapter()
        viewModel.saveRead()
        curChapterChanged()
    }

    override fun clickCenter() {
        if (readAloudStatus != Status.STOP) {
            ReadAloudDialog().show(supportFragmentManager, "readAloud")
        } else {
            read_menu.runMenuIn()
        }
    }

    /**
     * 朗读按钮
     */
    private fun onClickReadAloud() {
        if (!ReadAloudService.isRun) {
            readAloudStatus = Status.STOP
            SystemUtils.ignoreBatteryOptimization(this)
        }
        when (readAloudStatus) {
            Status.STOP -> readAloud()
            Status.PLAY -> ReadAloudService.pause(this)
            Status.PAUSE -> ReadAloudService.resume(this)
        }
    }

    /**
     * 朗读
     */
    private fun readAloud(play: Boolean = true) {
        val book = viewModel.bookData.value
        val textChapter = viewModel.curTextChapter
        if (book != null && textChapter != null) {
            val key = System.currentTimeMillis().toString()
            IntentDataHelp.putData(key, textChapter)
            ReadAloudService.play(
                this, book.name, textChapter.title,
                viewModel.durPageIndex, key, play
            )
        }
    }

    /**
     * 设置屏幕亮度
     */
    private fun setScreenBrightness(value: Int) {
        var brightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (this.getPrefBoolean("brightnessAuto").not()) {
            brightness = value.toFloat()
            if (brightness < 1f) brightness = 1f
            brightness /= 255f
        }
        val params = window.attributes
        params.screenBrightness = brightness
        window.attributes = params
    }

    override fun onColorSelected(dialogId: Int, color: Int) = with(ReadBookConfig.getConfig()) {
        when (dialogId) {
            TEXT_COLOR -> {
                setTextColor(color)
                postEvent(Bus.UP_CONFIG, false)
            }
            BG_COLOR -> {
                setBg(0, "#${color.toHexString()}")
                ReadBookConfig.upBg()
                postEvent(Bus.UP_CONFIG, false)
            }
        }
    }

    override fun onDialogDismissed(dialogId: Int) {

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                requestCodeEditSource -> viewModel.upBookSource()
            }
        }
    }

    override fun finish() {
        viewModel.bookData.value?.let {
            if (!viewModel.inBookshelf) {
                this.alert(title = getString(R.string.add_to_shelf)) {
                    message = getString(R.string.check_add_bookshelf, it.name)
                    okButton { viewModel.inBookshelf = true }
                    noButton { viewModel.removeFromBookshelf { super.finish() } }
                }.show().applyTint()
            } else {
                super.finish()
            }
        } ?: super.finish()
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<Int>(Bus.ALOUD_STATE) {
            readAloudStatus = it
            if (it == Status.STOP || it == Status.PAUSE) {
                viewModel.curTextChapter?.let { textChapter ->
                    val page = textChapter.page(viewModel.durPageIndex)
                    if (page != null && page.text is SpannableStringBuilder) {
                        page.text.removeSpan(ChapterProvider.readAloudSpan)
                        page_view.upContent()
                    }
                }
            }
        }
        observeEvent<String>(Bus.TIME_CHANGED) { page_view.upTime() }
        observeEvent<Int>(Bus.BATTERY_CHANGED) { page_view.upBattery(it) }
        observeEvent<BookChapter>(Bus.OPEN_CHAPTER) {
            viewModel.openChapter(it)
            page_view.upContent()
        }
        observeEvent<Boolean>(Bus.READ_ALOUD_BUTTON) {
            if (it) {
                onClickReadAloud()
            } else {
                readAloud(readAloudStatus == Status.PLAY)
            }
        }
        observeEvent<Boolean>(Bus.UP_CONFIG) {
            Help.upSystemUiVisibility(window, !read_menu.isVisible)
            page_view.upBg()
            content_view.upStyle()
            page_view.upStyle()
            if (it) {
                loadContent()
            } else {
                page_view.upContent()
            }
        }
        observeEvent<Int>(Bus.TTS_START) { chapterStart ->
            launch(IO) {
                viewModel.curTextChapter?.let {
                    val pageStart = chapterStart - it.getReadLength(viewModel.durPageIndex)
                    it.page(viewModel.durPageIndex)?.upPageAloudSpan(pageStart)
                    withContext(Main) {
                        page_view.upContent()
                    }
                }
            }
        }
        observeEvent<Boolean>(Bus.TTS_NEXT) {
            if (it) {
                moveToNextChapter()
            } else {
                viewModel.durPageIndex = viewModel.durPageIndex + 1
                page_view.upContent()
                viewModel.saveRead()
            }
        }
        observeEvent<String>(Bus.REPLACE) {
            toast(it)
        }
    }

}