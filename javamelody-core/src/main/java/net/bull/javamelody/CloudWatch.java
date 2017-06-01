/*
 * Copyright 2008-2017 by Emeric Vernat
 *
 *     This file is part of Java Melody.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.bull.javamelody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.cloudwatch.model.AmazonCloudWatchException;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.MetricDatum;
import com.amazonaws.services.cloudwatch.model.PutMetricDataRequest;

/**
 * Publish chart data to <a href='https://aws.amazon.com/cloudwatch/'>AWS CloudWatch</a>.
 * @author Emeric Vernat
 */
class CloudWatch {
	private final String cloudWatchNamespace;
	private final AmazonCloudWatch awsCloudWatch;
	private final String prefix;
	private final List<Dimension> dimensions = new ArrayList<Dimension>();

	private final List<MetricDatum> buffer = new ArrayList<MetricDatum>();
	private long lastTime;
	private Date lastTimestamp;

	CloudWatch(AmazonCloudWatch cloudWatch, String cloudWatchNamespace, String prefix,
			String application, String hostname) {
		super();
		assert cloudWatch != null;
		assert cloudWatchNamespace != null && !cloudWatchNamespace.startsWith("AWS/")
				&& cloudWatchNamespace.length() > 0 && cloudWatchNamespace.length() <= 255;
		assert prefix != null;
		assert application == null || application.length() >= 1 && application.length() <= 255;
		assert hostname == null || hostname.length() >= 1 && hostname.length() <= 255;

		this.awsCloudWatch = cloudWatch;
		this.cloudWatchNamespace = cloudWatchNamespace;
		this.prefix = prefix;
		// A dimension is like a tag which can be used to filter metrics in the CloudWatch UI.
		// Name and value of dimensions have min length 1 and max length 255.
		if (application != null) {
			dimensions.add(new Dimension().withName("application").withValue(application));
		}
		if (hostname != null) {
			dimensions.add(new Dimension().withName("hostname").withValue(hostname));
		}
	}

	/**
	 * New CloudWatch with DefaultAWSCredentialsProviderChain (and DefaultAwsRegionProviderChain) configured either by :
	 * <ul>
	 *   <li>Environment Variables -
	 *      <code>AWS_ACCESS_KEY_ID</code> and <code>AWS_SECRET_ACCESS_KEY</code>
	 *      (RECOMMENDED since they are recognized by all the AWS SDKs and CLI except for .NET),
	 *      or <code>AWS_ACCESS_KEY</code> and <code>AWS_SECRET_KEY</code> (only recognized by Java SDK)
	 *   </li>
	 *   <li>Java System Properties - aws.accessKeyId and aws.secretKey</li>
	 *   <li>Credential profiles file at the default location (~/.aws/credentials) shared by all AWS SDKs and the AWS CLI</li>
	 *   <li>Credentials delivered through the Amazon EC2 container service if AWS_CONTAINER_CREDENTIALS_RELATIVE_URI" environment variable is set
	 *   and security manager has permission to access the variable,</li>
	 *   <li>Instance profile credentials delivered through the Amazon EC2 metadata service</li>
	 * </ul>
	 * (idem for AWS region)
	 * 
	 * @param cloudWatchNamespace CloudWatch Namespace such as "MyCompany/MyAppDomain"
	 * 		(Namespace of Amazon EC2 is "AWS/EC2", but "AWS/*" is reserved for AWS products)
	 * @param prefix Prefix such as "javamelody."
	 * @param application Application such as /testapp
	 * @param hostname Hostname such as www.host.com
	 */
	CloudWatch(String cloudWatchNamespace, String prefix, String application, String hostname) {
		this(AmazonCloudWatchClientBuilder.defaultClient(), cloudWatchNamespace, prefix,
				application, hostname);
	}

	// exemple en spécifiant credentials, region et endpoint
	//	CloudWatch(String cloudWatchNamespace, AWSCredentialsProvider provider, String region) {
	//		this(cloudWatchNamespace, AmazonCloudWatchClientBuilder.standard()
	//				.withCredentials(provider)
	//				.withRegion(region)
	//				.withEndpointConfiguration(new EndpointConfiguration("monitoring.amazonaws.com"))
	//				.build(), ...);
	//	}

	static CloudWatch getInstance() {
		final String cloudWatchNamespace = Parameters.getParameter(Parameter.CLOUDWATCH_NAMESPACE);
		if (cloudWatchNamespace != null) {
			if (cloudWatchNamespace.startsWith("AWS/")) {
				// http://docs.aws.amazon.com/AmazonCloudWatch/latest/APIReference/API_PutMetricData.html
				throw new IllegalArgumentException(
						"CloudWatch namespaces starting with \"AWS/\" are reserved for use by AWS products.");
			}
			final String prefix = "javamelody.";
			final String contextPath = Parameters.getContextPath(Parameters.getServletContext());
			final String hostName = Parameters.getHostName();
			return new CloudWatch(cloudWatchNamespace, prefix, contextPath, hostName);
		}
		return null;
	}

	void addValue(String metric, double value) {
		assert metric != null;
		final long timeInSeconds = System.currentTimeMillis() / 1000;
		if (lastTime != timeInSeconds) {
			lastTimestamp = new Date();
			lastTime = timeInSeconds;
		}
		// http://docs.amazonwebservices.com/AmazonCloudWatch/latest/APIReference/API_MetricDatum.html
		// In theory, large values are rejected, but the maximum is so large that we don't bother to verify.
		final MetricDatum metricDatum = new MetricDatum().withMetricName(prefix + metric)
				.withDimensions(dimensions).withTimestamp(lastTimestamp).withValue(value);
		//.withUnit("None")
		synchronized (buffer) {
			buffer.add(metricDatum);
		}
	}

	void send() throws IOException {
		final List<MetricDatum> datumList;
		synchronized (buffer) {
			datumList = new ArrayList<MetricDatum>(buffer);
			buffer.clear();
		}

		// note: Each PutMetricData request is limited to 40 KB in size for HTTP POST requests.
		final PutMetricDataRequest request = new PutMetricDataRequest()
				.withNamespace(cloudWatchNamespace).withMetricData(datumList);
		try {
			awsCloudWatch.putMetricData(request);
		} catch (final AmazonCloudWatchException e) {
			throw new IOException("Error connecting to AWS CloudWatch", e);
		}
	}

	void stop() {
		awsCloudWatch.shutdown();
	}
}