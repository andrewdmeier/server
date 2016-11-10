/*
 * MongoWP - ToroDB-poc: Packaging generics
 * Copyright © 2014 8Kdata Technology (www.8kdata.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.torodb.packaging.config.validation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.torodb.packaging.config.model.protocol.mongo.AbstractReplication;

public class NoDuplicatedReplNameValidator implements ConstraintValidator<NoDuplicatedReplName, List<AbstractReplication>> {
	
	@Override
	public void initialize(NoDuplicatedReplName constraintAnnotation) {
	}

	@Override
	public boolean isValid(List<AbstractReplication> value, ConstraintValidatorContext context) {
		if (value != null) {
			Set<String> replNameSet = new HashSet<>();
			for (AbstractReplication replication : value) {
				if (!replNameSet.add(replication.getReplSetName())) {
					return false;
				}
			}
		}

		return true;
	}
}
