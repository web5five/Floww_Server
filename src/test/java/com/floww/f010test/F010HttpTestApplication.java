package com.floww.f010test;

import com.floww.server.DevAuthFilter;
import com.floww.server.KilnClient;
import com.floww.server.aidraft.AiDraftHttpController;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Isolated embedded HTTP slice; it does not enter the production configuration search path. */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
@Import({AiDraftHttpController.class, DevAuthFilter.class, KilnClient.class})
public class F010HttpTestApplication { }
