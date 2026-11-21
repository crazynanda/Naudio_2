package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.kotlinytmusicscraper.models.response.AccountSwitcherEndpointResponse
import com.maxrave.kotlinytmusicscraper.models.response.extractAccountItems
import com.maxrave.kotlinytmusicscraper.models.response.jsonShape
import com.maxrave.kotlinytmusicscraper.models.response.toListAccountInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The account switcher answers which account a cookie belongs to. That answer is what tells the
 * sign-in that it is finished, so a shape it no longer matches must not read as "no account":
 * the sign-in would report a failure for a session that YouTube accepted.
 */
class AccountSwitcherParserTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    private fun accountsOf(body: String): List<String> {
        val root = json.parseToJsonElement(body).jsonObject
        val typed =
            json.decodeFromJsonElement<AccountSwitcherEndpointResponse>(root).toListAccountInfo()
        val accounts = typed.ifEmpty { extractAccountItems(root, json) }
        return accounts.map { it.name }
    }

    @Test
    fun readsTheAccountOfTheKnownShape() {
        val body =
            """
            {
              "code": "SUCCESS",
              "data": {
                "contents": [
                  {
                    "accountSectionListRenderer": {
                      "contents": [
                        {
                          "accountItemSectionRenderer": {
                            "contents": [
                              {
                                "accountItem": {
                                  "accountName": { "simpleText": "Naudio Tester" },
                                  "channelHandle": { "simpleText": "@naudio" }
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  }
                ]
              }
            }
            """.trimIndent()

        assertEquals(listOf("Naudio Tester"), accountsOf(body))
    }

    @Test
    fun readsTheAccountWhenTheResponseNestsItDifferently() {
        // A section in front of the account section, or a wrapper around it, leaves the direct
        // path empty. The account is still in the answer.
        val body =
            """
            {
              "code": "SUCCESS",
              "data": {
                "contents": [
                  {
                    "someNewSectionRenderer": {
                      "contents": [
                        {
                          "accountItemSectionRenderer": {
                            "contents": [
                              {
                                "accountItem": {
                                  "accountName": { "simpleText": "Naudio Tester" },
                                  "channelHandle": { "simpleText": "@naudio" }
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  }
                ]
              }
            }
            """.trimIndent()

        assertEquals(listOf("Naudio Tester"), accountsOf(body))
    }

    @Test
    fun readsTheAccountWhenTheNameIsSentAsRuns() {
        val body =
            """
            {
              "code": "SUCCESS",
              "data": {
                "contents": [
                  {
                    "accountSectionListRenderer": {
                      "contents": [
                        {
                          "accountItemSectionRenderer": {
                            "contents": [
                              {
                                "accountItem": {
                                  "accountName": { "runs": [ { "text": "Naudio " }, { "text": "Tester" } ] },
                                  "channelHandle": { "simpleText": "@naudio" }
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  }
                ]
              }
            }
            """.trimIndent()

        assertEquals(listOf("Naudio Tester"), accountsOf(body))
    }

    @Test
    fun readsThePageIdAndTheAccountIndexOfTheSession() {
        val body =
            """
            {
              "code": "SUCCESS",
              "data": {
                "contents": [
                  {
                    "accountSectionListRenderer": {
                      "contents": [
                        {
                          "accountItemSectionRenderer": {
                            "contents": [
                              {
                                "accountItem": {
                                  "accountName": { "simpleText": "Naudio Tester" },
                                  "channelHandle": { "simpleText": "@naudio" },
                                  "onBehalfOfParameter": "brand_channel_1",
                                  "serviceEndpoint": {
                                    "selectActiveIdentityEndpoint": {
                                      "supportedTokens": [
                                        { "pageIdToken": { "pageId": "brand_channel_1" } },
                                        {
                                          "accountSigninToken": {
                                            "signinUrl": "/signin?action_handle_signin=true&authuser=1&pageid=brand_channel_1&next=%2F"
                                          }
                                        }
                                      ]
                                    }
                                  }
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  }
                ]
              }
            }
            """.trimIndent()

        val root = json.parseToJsonElement(body).jsonObject
        val account =
            json
                .decodeFromJsonElement<AccountSwitcherEndpointResponse>(root)
                .toListAccountInfo()
                .single()

        assertEquals("brand_channel_1", account.pageId)
        assertEquals(1, account.authUser)
    }

    @Test
    fun readsNoAccountWhenYouTubeNamedNone() {
        val body =
            """
            { "code": "SUCCESS", "data": { "contents": [] } }
            """.trimIndent()

        assertTrue(accountsOf(body).isEmpty())
    }

    @Test
    fun theShapeOfTheAnswerHoldsTheKeysAndNotAValue() {
        // The shape is what the log may carry: the body itself holds the account of the user.
        val body =
            """
            {
              "code": "SUCCESS",
              "data": {
                "contents": [
                  { "accountSectionListRenderer": { "contents": [ { "accountItem": { "accountName": { "simpleText": "Naudio Tester" } } } ] } }
                ]
              }
            }
            """.trimIndent()

        val shape = jsonShape(json.parseToJsonElement(body).jsonObject)

        assertTrue(shape.contains("accountSectionListRenderer"))
        assertTrue(shape.contains("accountItem"))
        assertFalse(shape.contains("Naudio Tester"))
        assertFalse(shape.contains("SUCCESS"))
    }
}
