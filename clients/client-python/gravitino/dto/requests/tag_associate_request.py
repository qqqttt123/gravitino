# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

from dataclasses_json import config, dataclass_json

from gravitino.api.tag.tag_value import TagValue
from gravitino.rest.rest_message import RESTRequest
from gravitino.utils.precondition import Precondition
from gravitino.utils.string_utils import StringUtils


@dataclass_json
@dataclass
class TagsAssociateRequest(RESTRequest):
    """
    Represents a request to associate tags.
    """

    _tags_to_add: Optional[list[TagValue]] = field(
        default=None, metadata=config(field_name="tagsToAdd")
    )
    _tags_to_remove: Optional[list[TagValue]] = field(
        default=None, metadata=config(field_name="tagsToRemove")
    )

    def __post_init__(self) -> None:
        self._tags_to_add = self._to_tag_values(self._tags_to_add)
        self._tags_to_remove = self._to_tag_values(self._tags_to_remove)

    @property
    def tags_to_add(self) -> Optional[list[TagValue]]:
        """
        Gets the tags to add.
        """
        return self._tags_to_add

    @property
    def tags_to_remove(self) -> Optional[list[TagValue]]:
        """
        Gets the tags to remove.
        """
        return self._tags_to_remove

    def validate(self) -> None:
        """
        Validates the request.
        """
        Precondition.check_argument(
            self._tags_to_add is not None or self._tags_to_remove is not None,
            "tagsToAdd and tagsToRemove cannot both be null",
        )

        self._validate_tags(self._tags_to_add, "tagsToAdd")
        self._validate_tags(self._tags_to_remove, "tagsToRemove")

    def _validate_tags(self, tags: Optional[list[TagValue]], field_name: str) -> None:
        if tags is None:
            return

        Precondition.check_argument(
            all(
                tag_value is not None and StringUtils.is_not_blank(tag_value.name)
                for tag_value in tags
            ),
            f"{field_name} must not contain null or empty tag names",
        )
        Precondition.check_argument(
            all(
                tag_value.value is None or StringUtils.is_not_blank(tag_value.value)
                for tag_value in tags
            ),
            f"{field_name} must not contain empty tag values",
        )

    @staticmethod
    def _to_tag_values(tags):
        if tags is None:
            return None

        return [
            tag if isinstance(tag, TagValue) else TagValue.no_value(tag) for tag in tags
        ]
