package ttd.site.generator;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.ReflectionUtils;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;

@Log4j2
@Configuration
@RequiredArgsConstructor
class Step5Configuration {

	private final static String STEP_NAME = "step5";

	private final SiteGeneratorConfigurationProperties properties;

	private final StepBuilderFactory sbf;

	private final JdbcTemplate template;

	private final DataSource dataSource;

	private final TemplateService templateService;

	@Bean(STEP_NAME + "Reader")
	JdbcCursorItemReader<YearMonth> reader() {
		return new JdbcCursorItemReaderBuilder<YearMonth>()//
				.sql("select * from bookmark_years_months bym ")//
				.dataSource(this.dataSource)//
				.rowMapper((rs, i) -> new YearMonth(rs.getInt("year"), rs.getInt("month"))) //
				.name(getClass().getSimpleName() + "#reader") //
				.build();
	}

	@Bean(STEP_NAME + "Processor")
	ItemProcessor<YearMonth, Bookmarks> processor() {
		return ym -> {
			var bookmarkList =  template.query(
					"select * from bookmark b where date_part('month', time) = ? and date_part('year', time) = ? ",
					new Object[] { ym.getMonth(), ym.getYear() }, new BookmarkRowMapper());
			return new Bookmarks(ym, bookmarkList);
		};
	}

	@Bean(STEP_NAME + "Writer")
	ItemWriter<Bookmarks> writer() {
		return items -> items.forEach(bm -> writeYearAndMonthBlog(bm.getYearMonth(), bm.getBookmarks()));
	}

	@Bean(STEP_NAME)
	Step step() {
		return this.sbf//
				.get(STEP_NAME)//
				.<YearMonth, Bookmarks>chunk(100)//
				.reader(reader())//
				.processor(processor())//
				.writer(writer())//
				.build();
	}

	private void writeYearAndMonthBlog(YearMonth ym, List<Bookmark> bookmarks) {
		var yearMonthKey = (ym).toString();
		log.debug("writing " + yearMonthKey + " with " + bookmarks.size() + " articles");
		var bookmarksByDate = new HashMap<String, List<Link>>();
		var stringListFunction = new Function<String, List<Link>>() {

			@Override
			public List<Link> apply(String s) {
				return new ArrayList<>();
			}
		};
		bookmarks.forEach(bm -> {
			var key = DateUtils.formatYearMonthDay(bm.getTime());
			var link = new Link(Long.toString(bm.getBookmarkId()), bm.getHref(), bm.getDescription(), bm.getTime());
			bookmarksByDate.computeIfAbsent(key, stringListFunction).add(link);
		});
		var file = new File(this.properties.getContentDirectory(), yearMonthKey + ".html");
		var monthlyHtml = templateService.monthly(ym, bookmarksByDate);
		try (var bw = new BufferedWriter(new FileWriter(file))) {
			bw.write(monthlyHtml);
		}
		catch (IOException e) {
			ReflectionUtils.rethrowRuntimeException(e);
		}
	}

}
