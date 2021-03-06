package org.ironrhino.core.security.verfication;

import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.userdetails.UserDetails;

import com.fasterxml.jackson.annotation.JsonIgnore;

public interface VerificationAware extends UserDetails {

	@JsonIgnore
	default boolean isVerificationRequired() {
		return true;
	}

	@JsonIgnore
	default boolean isPasswordRequired() {
		return StringUtils.isNotBlank(getPassword());
	}

	@JsonIgnore
	String getReceiver();

}
