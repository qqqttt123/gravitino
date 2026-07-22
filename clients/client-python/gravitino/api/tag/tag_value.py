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


@dataclass_json
@dataclass(frozen=True)
class TagValue:
    """Represents a tag assignment value with an optional value."""

    _name: str = field(metadata=config(field_name="name"))
    _value: Optional[str] = field(default=None, metadata=config(field_name="value"))

    @staticmethod
    def of(name: str, value: str) -> TagValue:
        """Create a tag value."""
        return TagValue(name, value)

    @staticmethod
    def no_value(name: str) -> TagValue:
        """Create a tag assignment with no value."""
        return TagValue(name, None)

    @property
    def name(self) -> str:
        """Return the tag name."""
        return self._name

    @property
    def value(self) -> Optional[str]:
        """Return the assignment value, or None for an assignment with no value."""
        return self._value
