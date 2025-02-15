package io.legado.app.data.entities

import android.os.Parcelable
import android.text.TextUtils
import androidx.room.*
import io.legado.app.constant.AppConst
import io.legado.app.constant.BookType
import io.legado.app.data.entities.rule.*
import io.legado.app.help.AppConfig
import io.legado.app.help.CacheManager
import io.legado.app.help.JsExtensions
import io.legado.app.help.http.CookieStore
import io.legado.app.utils.*
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import splitties.init.appCtx
import javax.script.SimpleBindings

@Parcelize
@TypeConverters(BookSource.Converters::class)
@Entity(
    tableName = "book_sources",
    indices = [(Index(value = ["bookSourceUrl"], unique = false))]
)
data class BookSource(
    var bookSourceName: String = "",                // 名称
    var bookSourceGroup: String? = null,            // 分组
    @PrimaryKey
    var bookSourceUrl: String = "",                 // 地址，包括 http/https
    var bookSourceType: Int = BookType.default,     // 类型，0 文本，1 音频
    var bookUrlPattern: String? = null,             // 详情页url正则
    var concurrentRate: String? = null,             //并发率
    var customOrder: Int = 0,                       // 手动排序编号
    var enabled: Boolean = true,                    // 是否启用
    var enabledExplore: Boolean = true,             // 启用发现
    var header: String? = null,                     // 请求头
    var loginUrl: String? = null,                   // 登录地址
    var bookSourceComment: String? = null,            // 注释
    var lastUpdateTime: Long = 0,                   // 最后更新时间，用于排序
    var weight: Int = 0,                            // 智能排序的权重
    var exploreUrl: String? = null,                 // 发现url
    var ruleExplore: ExploreRule? = null,           // 发现规则
    var searchUrl: String? = null,                  // 搜索url
    var ruleSearch: SearchRule? = null,             // 搜索规则
    var ruleBookInfo: BookInfoRule? = null,         // 书籍信息页规则
    var ruleToc: TocRule? = null,                   // 目录页规则
    var ruleContent: ContentRule? = null            // 正文页规则
) : Parcelable, JsExtensions {

    @delegate:Transient
    @delegate:Ignore
    @IgnoredOnParcel
    val loginRule by lazy {
        if (loginUrl.isJsonObject()) {
            return@lazy GSON.fromJsonObject<LoginRule>(loginUrl)
        } else {
            return@lazy LoginRule(url = loginUrl)
        }
    }

    @delegate:Transient
    @delegate:Ignore
    @IgnoredOnParcel
    val exploreKinds by lazy {
        val exploreUrl = exploreUrl ?: return@lazy emptyList()
        val kinds = arrayListOf<ExploreKind>()
        var ruleStr = exploreUrl
        if (ruleStr.isNotBlank()) {
            kotlin.runCatching {
                if (exploreUrl.startsWith("<js>", false)
                    || exploreUrl.startsWith("@js", false)
                ) {
                    val aCache = ACache.get(appCtx, "explore")
                    ruleStr = aCache.getAsString(bookSourceUrl) ?: ""
                    if (ruleStr.isBlank()) {
                        val bindings = SimpleBindings()
                        bindings["baseUrl"] = bookSourceUrl
                        bindings["java"] = this
                        bindings["cookie"] = CookieStore
                        bindings["cache"] = CacheManager
                        val jsStr = if (exploreUrl.startsWith("@")) {
                            exploreUrl.substring(3)
                        } else {
                            exploreUrl.substring(4, exploreUrl.lastIndexOf("<"))
                        }
                        ruleStr = AppConst.SCRIPT_ENGINE.eval(jsStr, bindings).toString().trim()
                        aCache.put(bookSourceUrl, ruleStr)
                    }
                }
                if (ruleStr.isJsonArray()) {
                    GSON.fromJsonArray<ExploreKind>(ruleStr)?.let {
                        kinds.addAll(it)
                    }
                } else {
                    ruleStr.split("(&&|\n)+".toRegex()).forEach { kindStr ->
                        val kindCfg = kindStr.split("::")
                        kinds.add(ExploreKind(kindCfg.first(), kindCfg.getOrNull(1)))
                    }
                }
            }.onFailure {
                kinds.add(ExploreKind(it.localizedMessage ?: ""))
            }
        }
        return@lazy kinds
    }

    override fun hashCode(): Int {
        return bookSourceUrl.hashCode()
    }

    override fun equals(other: Any?) =
        if (other is BookSource) other.bookSourceUrl == bookSourceUrl else false

    @Throws(Exception::class)
    fun getHeaderMap() = (HashMap<String, String>().apply {
        this[AppConst.UA_NAME] = AppConfig.userAgent
        header?.let {
            GSON.fromJsonObject<Map<String, String>>(
                when {
                    it.startsWith("@js:", true) ->
                        evalJS(it.substring(4)).toString()
                    it.startsWith("<js>", true) ->
                        evalJS(it.substring(4, it.lastIndexOf("<"))).toString()
                    else -> it
                }
            )?.let { map ->
                putAll(map)
            }
        }
    }) as Map<String, String>

    fun getSearchRule() = ruleSearch ?: SearchRule()

    fun getExploreRule() = ruleExplore ?: ExploreRule()

    fun getBookInfoRule() = ruleBookInfo ?: BookInfoRule()

    fun getTocRule() = ruleToc ?: TocRule()

    fun getContentRule() = ruleContent ?: ContentRule()

    fun addGroup(group: String) {
        bookSourceGroup?.let {
            if (!it.contains(group)) {
                bookSourceGroup = "$it,$group"
            }
        } ?: let {
            bookSourceGroup = group
        }
    }

    fun removeGroup(group: String) {
        bookSourceGroup?.splitNotBlank("[,;，；]".toRegex())?.toHashSet()?.let {
            it.remove(group)
            bookSourceGroup = TextUtils.join(",", it)
        }
    }

    /**
     * 执行JS
     */
    @Throws(Exception::class)
    private fun evalJS(jsStr: String): Any {
        val bindings = SimpleBindings()
        bindings["java"] = this
        bindings["cookie"] = CookieStore
        bindings["cache"] = CacheManager
        return AppConst.SCRIPT_ENGINE.eval(jsStr, bindings)
    }

    fun equal(source: BookSource) =
        equal(bookSourceName, source.bookSourceName)
                && equal(bookSourceUrl, source.bookSourceUrl)
                && equal(bookSourceGroup, source.bookSourceGroup)
                && bookSourceType == source.bookSourceType
                && equal(bookUrlPattern, source.bookUrlPattern)
                && equal(bookSourceComment, source.bookSourceComment)
                && enabled == source.enabled
                && enabledExplore == source.enabledExplore
                && equal(header, source.header)
                && equal(loginUrl, source.loginUrl)
                && equal(exploreUrl, source.exploreUrl)
                && equal(searchUrl, source.searchUrl)
                && getSearchRule() == source.getSearchRule()
                && getExploreRule() == source.getExploreRule()
                && getBookInfoRule() == source.getBookInfoRule()
                && getTocRule() == source.getTocRule()
                && getContentRule() == source.getContentRule()

    private fun equal(a: String?, b: String?) = a == b || (a.isNullOrEmpty() && b.isNullOrEmpty())

    class Converters {
        @TypeConverter
        fun loginRuleTString(loginRule: LoginRule?): String = GSON.toJson(loginRule)

        @TypeConverter
        fun stringToLoginRule(json: String?): LoginRule? {
            json ?: return null
            return if (json.isJsonObject()) {
                GSON.fromJsonObject(json)
            } else {
                LoginRule(url = json)
            }
        }

        @TypeConverter
        fun exploreRuleToString(exploreRule: ExploreRule?): String = GSON.toJson(exploreRule)

        @TypeConverter
        fun stringToExploreRule(json: String?) = GSON.fromJsonObject<ExploreRule>(json)

        @TypeConverter
        fun searchRuleToString(searchRule: SearchRule?): String = GSON.toJson(searchRule)

        @TypeConverter
        fun stringToSearchRule(json: String?) = GSON.fromJsonObject<SearchRule>(json)

        @TypeConverter
        fun bookInfoRuleToString(bookInfoRule: BookInfoRule?): String = GSON.toJson(bookInfoRule)

        @TypeConverter
        fun stringToBookInfoRule(json: String?) = GSON.fromJsonObject<BookInfoRule>(json)

        @TypeConverter
        fun tocRuleToString(tocRule: TocRule?): String = GSON.toJson(tocRule)

        @TypeConverter
        fun stringToTocRule(json: String?) = GSON.fromJsonObject<TocRule>(json)

        @TypeConverter
        fun contentRuleToString(contentRule: ContentRule?): String = GSON.toJson(contentRule)

        @TypeConverter
        fun stringToContentRule(json: String?) = GSON.fromJsonObject<ContentRule>(json)

    }
}