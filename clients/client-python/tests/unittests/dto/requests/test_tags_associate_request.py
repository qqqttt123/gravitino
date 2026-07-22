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

import json as _json
import unittest

from gravitino.api.tag.tag_value import TagValue
from gravitino.dto.requests.tag_associate_request import TagsAssociateRequest
from gravitino.exceptions.base import IllegalArgumentException


class TestTagsAssociateRequest(unittest.TestCase):
    def test_create_request(self) -> None:
        request = TagsAssociateRequest(
            [
                TagValue.no_value("tag_to_add_1"),
                TagValue.of("tag_to_add_2", "finance"),
            ],
            [TagValue.of("tag_to_remove_1", "risk")],
        )
        json_str = _json.dumps(
            {
                "tagsToAdd": [
                    {"name": "tag_to_add_1", "value": None},
                    {"name": "tag_to_add_2", "value": "finance"},
                ],
                "tagsToRemove": [{"name": "tag_to_remove_1", "value": "risk"}],
            }
        )

        self.assertEqual(json_str, request.to_json())
        deserialized_request = TagsAssociateRequest.from_json(json_str)

        self.assertTrue(isinstance(deserialized_request, TagsAssociateRequest))
        self.assertEqual("tag_to_add_1", deserialized_request.tags_to_add[0].name)
        self.assertIsNone(deserialized_request.tags_to_add[0].value)
        self.assertEqual("tag_to_add_2", deserialized_request.tags_to_add[1].name)
        self.assertEqual("finance", deserialized_request.tags_to_add[1].value)
        self.assertEqual("tag_to_remove_1", deserialized_request.tags_to_remove[0].name)
        self.assertEqual("risk", deserialized_request.tags_to_remove[0].value)

    def test_associate_request_validate(self) -> None:
        invalid_request1 = TagsAssociateRequest(
            None, None
        )  # pyright: ignore[reportArgumentType]
        invalid_request2 = TagsAssociateRequest(
            ["tag_to_add_1", " "], ["tag_to_remove_1", "tag_to_remove_2"]
        )
        invalid_request3 = TagsAssociateRequest(
            [TagValue.of("tag_to_add_1", " ")], None
        )

        with self.assertRaises(IllegalArgumentException):
            invalid_request1.validate()

        with self.assertRaises(IllegalArgumentException):
            invalid_request2.validate()

        with self.assertRaises(IllegalArgumentException):
            invalid_request3.validate()
