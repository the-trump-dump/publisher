package ttd.site.generator

import com.samskivert.mustache.Mustache
import com.samskivert.mustache.Template
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.springframework.beans.BeanUtils
import org.springframework.core.io.Resource
import org.springframework.util.Assert
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Collectors
import kotlin.Comparator


open class MustacheTemplateService(
		private val fileNameResolver: (String) -> String,
		private val compiler: Mustache.Compiler,
		daily: Resource,
		index: Resource,
		monthly: Resource,
		frame: Resource,
		list: Resource,
		years: Resource,
		year: Resource,
		charset: Charset
) :
		TemplateService {


	private val charset = charset.name()
	private val propertyDescriptors = BeanUtils.getPropertyDescriptors(Link::class.java)
	private val parser = Parser.builder().build()
	private val daily = createTemplate(daily)
	private val index = createTemplate(index)
	private val monthly = createTemplate(monthly)
	private val _list = createTemplate(list)
	private val _frame = createTemplate(frame)
	private val _years = createTemplate(years)
	private val _year = createTemplate(year)

	companion object {
		const val URL_MARKER = "_URL_"
		const val ID_MARKER = "_ID_"
		const val DESC_MARKER = "_DESC_"
	}

	data class KeyAndLinks(val key: String, val links: List<Map<String, Any>>)

	init {
		listOf(this.daily, this.index, this.monthly, this._list, this._frame).forEach {
			Assert.notNull(it, "the template should not be null")
		}
	}

	override fun monthly(yearMonth: YearMonth, links: Map<String, List<Link>>): String {
		return this.frame(monthlyWithoutFrame(yearMonth, links))
	}

	override fun monthlyWithoutFrame(yearMonth: YearMonth, links: Map<String, List<Link>>): String {
		val linkMaps: (List<Link>) -> List<Map<String, Any>> = {
			it.stream().map(this::buildMapForLink).collect(Collectors.toList())
		}
		val listOfKeyAndLinks =
				links
						.map { KeyAndLinks(it.key, linkMaps(it.value)) }
						.sortedWith(Comparator { o1, o2 -> o1.key.compareTo(o2.key) })
						.reversed()
		val map = mapOf("yearAndMonth" to yearMonth, "dates" to listOfKeyAndLinks)
		return this.monthly.execute(map)
	}

	override fun daily(date: Date, links: Collection<Link>): String {
		val context = this.buildDefaultContextFor(links)
		context["date"] = DateUtils.formatYearMonthDay(date)
		return this.frame(this.daily.execute(context))
	}

	/**
	 * this renders a page that has the years and the months, as well as the latest month's worth of content.
	 */
	override fun index(latest: YearMonth): String {
		val index = index.execute(mutableMapOf(
				"latest_date" to latest.toString(),
				"latest" to this.fileNameResolver("${latest}-latest.html")))
		return frame(index)
	}

	override fun years(yearMonths: List<YearMonth>): String {
		Assert.isTrue(yearMonths.isNotEmpty(), "there must be at least one element in the ${YearMonth::class.java.name} collection.")

		val yearToMonths = mutableMapOf<String, MutableList<YearMonth>>()
		yearMonths.forEach { yearToMonths.computeIfAbsent(it.year.toString() + "") { mutableListOf() }.add(it) }

		val sortedYears = ArrayList(yearToMonths.keys)
		sortedYears.sortWith(Comparator.naturalOrder())

		val htmlForEachYear = sortedYears
				.reversed()
				.map {
					val months = yearToMonths[it]!!
					months.sortedWith(java.util.Comparator { obj: YearMonth, other: YearMonth -> obj.compareTo(other) })
					_year.execute(mapOf("year" to it, "months" to months))
				}

		return this._years.execute(mapOf("years" to htmlForEachYear))
	}

	private fun frame(body: String) = this._frame.execute(mapOf("body" to body,
			"years" to fileNameResolver("years.include"),
			"built" to Instant.now().toString()))


	private fun buildMapForLink(lien: Link): Map<String, Any> {
		val linkMapForRendering = mutableMapOf<String, Any>()
		this.propertyDescriptors.forEach { pd ->
			linkMapForRendering[pd.name] = pd.readMethod.invoke(lien)
		}
		val inputDescription = lien.description
		val url = lien.href
		var template = "[_DESC_](_URL_)"
		val publishKey = lien.publishKey

		if (this.shouldProcessDescription(inputDescription)) {
			template = inputDescription
		}

		val html = this.buildHtml(template, url, publishKey, inputDescription)
		linkMapForRendering["html"] = html
		return linkMapForRendering
	}

	private fun createTemplate(resource: Resource): Template =
			InputStreamReader(resource.inputStream, this.charset)
					.use { this.compiler.compile(it) }

	private fun markdownToHtml(input: String): String {
		synchronized(this.parser) {
			val document = parser.parse(input)
			val renderer = HtmlRenderer.builder().build()
			return renderer.render(document)
		}
	}

	private fun shouldProcessDescription(inputDescription: String) =
			listOf(URL_MARKER, ID_MARKER, DESC_MARKER).firstOrNull { inputDescription.contains(it) } != null

	/* package private for testing */
	fun replaceString(x: String, find: String, replace: String): String {

		var inString = x
		var start: Int
		while (true) {
			start = inString.indexOf(find)
			if (start != -1) {
				val before = inString.substring(0, start)
				val after = inString.substring(start + find.length)
				inString = before + replace + after
			} else {
				break
			}
		}
		return inString
	}

	private fun buildHtml(template: String, href: String, pk: String, desc: String): String {
		val replacements = mapOf(URL_MARKER to href, ID_MARKER to pk, DESC_MARKER to desc)
		val atomicReference = AtomicReference<String>()
		atomicReference.set(template)
		replacements.forEach { (k, v) -> atomicReference.set(replaceString(atomicReference.get(), k, v)) }
		var html = markdownToHtml(atomicReference.get()).trim()
		if (html.startsWith("<p>") && html.endsWith("</p>")) {
			html = html.substring("<p>".length)
			html = html.substring(0, html.lastIndexOf("</p>"))
		}
		return html
	}

	private fun buildDefaultContextFor(links: Collection<Link>): MutableMap<String, Any> =
			mutableMapOf("links" to links.stream().map(this::buildMapForLink).collect(Collectors.toList()))


}
