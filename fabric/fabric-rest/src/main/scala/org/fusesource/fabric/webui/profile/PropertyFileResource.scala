/*
 * Copyright 2010 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package org.fusesource.fabric.webui.profile

import collection.JavaConversions._
import org.fusesource.fabric.api.Profile
import org.codehaus.jackson.annotate.JsonProperty
import javax.ws.rs.{PathParam, Path}
import org.fusesource.fabric.webui.BaseResource

/**
 *
 */
class CreatePropertyEntryDTO {
  @JsonProperty
  var id: String = _
  @JsonProperty
  var value: Array[Byte] = _
}

class PropertyFileResource(
                            profile: Profile,
                            _id: String,
                            _value: Array[Byte]) extends BaseResource {

  @JsonProperty
  def id = _id

  @JsonProperty
  def value = _value
}

class PropertyFilesResource(profile: Profile) extends BaseResource {

  @JsonProperty
  def entries = profile.getFileConfigurations
    .map {
    case (k, v) =>
      new PropertyFileResource(profile, k, v)
  }.toArray

  @Path("{id}")
  def entry(@PathParam("id") id: String) = entries.find(_.id == id).getOrElse(not_found)

}

