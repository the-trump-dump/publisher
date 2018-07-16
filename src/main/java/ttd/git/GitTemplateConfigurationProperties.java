package ttd.git;

import lombok.Data;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

import javax.annotation.PostConstruct;
import java.io.File;

/**
	* @author <a href="mailto:josh@joshlong.com">Josh Long</a>
	*/
@Data
@Log4j2
@ConfigurationProperties("git")
public class GitTemplateConfigurationProperties {

		private File localCloneDirectory = new File(System.getProperty("user.home"), "blog-clone");
		private String uri;
		private final Ssh ssh = new Ssh();
		private final Http http = new Http();

		@Data
		public static class Ssh {
				private boolean enabled;
				private String password;
		}

		@Data
		public static class Http {
				private String username = null, password = "";
				private boolean enabled;
		}

}
