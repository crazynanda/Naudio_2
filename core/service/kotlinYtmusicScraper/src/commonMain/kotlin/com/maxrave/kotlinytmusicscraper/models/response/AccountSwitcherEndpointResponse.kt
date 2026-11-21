package com.maxrave.kotlinytmusicscraper.models.response

import com.maxrave.kotlinytmusicscraper.models.AccountInfo
import com.maxrave.kotlinytmusicscraper.models.Run
import com.maxrave.kotlinytmusicscraper.models.Thumbnail
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class AccountSwitcherEndpointResponse(
    val code: String?,
    val data: AccountSwitcherData?,
)

@Serializable
data class AccountSwitcherData(
    val actions: List<AccountSwitcherAction?>?,
    val contents: List<AccountSwitcherSection>?,
    val responseContext: AccountSwitcherResponseContext?,
    val selectText: AccountSwitcherSelectText?,
)

@Serializable
data class AccountSwitcherAction(
    val getMultiPageMenuAction: AccountSwitcherGetMultiPageMenuAction?,
)

@Serializable
data class AccountSwitcherGetMultiPageMenuAction(
    val menu: AccountSwitcherMenu?,
)

@Serializable
data class AccountSwitcherMenu(
    val multiPageMenuRenderer: AccountSwitcherMultiPageMenuRenderer?,
)

@Serializable
data class AccountSwitcherMultiPageMenuRenderer(
    val footer: AccountSwitcherFooter?,
    val header: AccountSwitcherMenuHeader?,
    val sections: List<AccountSwitcherSection?>?,
    val style: String?,
)

@Serializable
data class AccountSwitcherFooter(
    val multiPageMenuSectionRenderer: AccountSwitcherMultiPageMenuSectionRenderer?,
)

@Serializable
data class AccountSwitcherMultiPageMenuSectionRenderer(
    val items: List<AccountSwitcherMenuItem?>?,
)

@Serializable
data class AccountSwitcherMenuItem(
    val compactLinkRenderer: AccountSwitcherCompactLinkRenderer?,
)

@Serializable
data class AccountSwitcherCompactLinkRenderer(
    val icon: AccountSwitcherCompactLinkIcon?,
    val navigationEndpoint: AccountSwitcherCompactLinkNavigationEndpoint?,
    val style: String?,
    val title: AccountSwitcherCompactLinkTitle?,
)

@Serializable
data class AccountSwitcherCompactLinkIcon(
    val iconType: String?,
)

@Serializable
data class AccountSwitcherCompactLinkNavigationEndpoint(
    val signOutEndpoint: AccountSwitcherSignOutEndpoint?,
    val urlEndpoint: AccountSwitcherUrlEndpoint?,
)

@Serializable
data class AccountSwitcherSignOutEndpoint(
    val hack: Boolean?,
)

@Serializable
data class AccountSwitcherUrlEndpoint(
    val url: String?,
)

@Serializable
data class AccountSwitcherCompactLinkTitle(
    val runs: List<Run?>?,
)

@Serializable
data class AccountSwitcherMenuHeader(
    val simpleMenuHeaderRenderer: AccountSwitcherSimpleMenuHeaderRenderer?,
)

@Serializable
data class AccountSwitcherSimpleMenuHeaderRenderer(
    val backButton: AccountSwitcherBackButton?,
    val title: AccountSwitcherSimpleMenuTitle?,
)

@Serializable
data class AccountSwitcherBackButton(
    val buttonRenderer: AccountSwitcherButtonRenderer?,
)

@Serializable
data class AccountSwitcherButtonRenderer(
    val accessibility: AccountSwitcherButtonAccessibility?,
    val accessibilityData: AccountSwitcherButtonAccessibilityDataWrapper?,
    val icon: AccountSwitcherButtonIcon?,
    val isDisabled: Boolean?,
    val size: String?,
    val style: String?,
)

@Serializable
data class AccountSwitcherButtonAccessibility(
    val label: String?,
)

@Serializable
data class AccountSwitcherButtonAccessibilityDataWrapper(
    val accessibilityData: AccountSwitcherButtonAccessibilityData?,
)

@Serializable
data class AccountSwitcherButtonAccessibilityData(
    val label: String?,
)

@Serializable
data class AccountSwitcherButtonIcon(
    val iconType: String?,
)

@Serializable
data class AccountSwitcherSimpleMenuTitle(
    val runs: List<Run?>?,
)

@Serializable
data class AccountSwitcherSection(
    val accountSectionListRenderer: AccountSectionListRenderer?,
)

@Serializable
data class AccountSectionListRenderer(
    val contents: List<AccountSectionContent?>?,
    val header: AccountSectionHeader?,
)

@Serializable
data class AccountSectionContent(
    val accountItemSectionRenderer: AccountItemSectionRenderer?,
)

@Serializable
data class AccountItemSectionRenderer(
    val contents: List<AccountItemContent?>?,
    val header: AccountItemSectionHeaderWrapper?,
)

@Serializable
data class AccountItemContent(
    val accountItem: AccountItem?,
)

private val authUserRegex = Regex("[?&]authuser=(\\d+)")

@Serializable
data class AccountItem(
    val onBehalfOfParameter: String?,
    val accountByline: AccountByline?,
    val accountLogDirectiveInts: List<Int?>?,
    val accountName: AccountName?,
    val accountPhoto: AccountPhoto?,
    val channelHandle: ChannelHandle?,
    val hasChannel: Boolean?,
    val isDisabled: Boolean?,
    val isSelected: Boolean?,
    val mobileBanner: MobileBanner?,
    val serviceEndpoint: AccountItemServiceEndpoint?,
    val unlimitedStatus: List<AccountItemUnlimitedStatus?>?,
) {
    fun toAccountInfo(email: String): AccountInfo? {
        val tokens = serviceEndpoint?.selectActiveIdentityEndpoint?.supportedTokens
        return AccountInfo(
            name = accountName?.text ?: return null,
            email = email,
            pageId =
                onBehalfOfParameter
                    ?: tokens
                        ?.firstOrNull { it?.pageIdToken != null }
                        ?.pageIdToken
                        ?.pageId,
            // The switcher tells which signed-in Google account owns each channel only
            // through the `authuser=N` param of its sign-in URL, e.g.
            // "/signin?action_handle_signin=true&authuser=1&pageid=1032...&next=%2F".
            authUser =
                tokens
                    ?.firstOrNull { it?.accountSigninToken != null }
                    ?.accountSigninToken
                    ?.signinUrl
                    ?.let { authUserRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    ?: 0,
            thumbnails = accountPhoto?.thumbnails?.filterNotNull() ?: emptyList(),
        )
    }
}

@Serializable
data class AccountByline(
    val simpleText: String? = null,
)

@Serializable
data class AccountName(
    val simpleText: String? = null,
    val runs: List<Run?>? = null,
) {
    /**
     * The display name of the account.
     *
     * The renderer used to carry it as a plain `simpleText` and now sends it as a run list.
     * Both are read, because dropping the account because one of them is missing is what turns a
     * successful sign-in into "no account".
     */
    val text: String?
        get() =
            simpleText?.takeIf { it.isNotBlank() }
                ?: runs?.mapNotNull { it?.text }?.joinToString("")?.takeIf { it.isNotBlank() }
}

@Serializable
data class AccountPhoto(
    val thumbnails: List<Thumbnail?>?,
)

@Serializable
data class ChannelHandle(
    val simpleText: String? = null,
)

@Serializable
data class MobileBanner(
    val thumbnails: List<Thumbnail?>?,
)

@Serializable
data class AccountItemServiceEndpoint(
    val selectActiveIdentityEndpoint: SelectActiveIdentityEndpoint?,
)

@Serializable
data class AccountItemUnlimitedStatus(
    val runs: List<Run?>?,
)

@Serializable
data class AccountItemSectionHeaderWrapper(
    val accountItemSectionHeaderRenderer: AccountItemSectionHeaderRenderer?,
)

@Serializable
data class AccountItemSectionHeaderRenderer(
    val title: AccountItemSectionHeaderTitle?,
)

@Serializable
data class AccountItemSectionHeaderTitle(
    val runs: List<Run?>?,
)

@Serializable
data class AccountSectionHeader(
    val accountsDialogHeaderRenderer: AccountsDialogHeaderRenderer?,
    val googleAccountHeaderRenderer: GoogleAccountHeaderRenderer?,
)

@Serializable
data class AccountsDialogHeaderRenderer(
    val text: AccountsDialogHeaderText?,
)

@Serializable
data class AccountsDialogHeaderText(
    val runs: List<Run?>?,
)

@Serializable
data class GoogleAccountHeaderRenderer(
    val email: GoogleAccountEmail?,
    val name: GoogleAccountName?,
)

@Serializable
data class GoogleAccountEmail(
    val runs: List<Run?>?,
)

@Serializable
data class GoogleAccountName(
    val runs: List<Run?>?,
)

@Serializable
data class AccountSwitcherResponseContext(
    val serviceTrackingParams: List<AccountSwitcherServiceTrackingParam?>?,
)

@Serializable
data class AccountSwitcherServiceTrackingParam(
    val params: List<AccountSwitcherTrackingParam?>?,
    val service: String?,
)

@Serializable
data class AccountSwitcherTrackingParam(
    val key: String?,
    val value: String?,
)

@Serializable
data class AccountSwitcherSelectText(
    val runs: List<Run?>?,
)

@Serializable
data class SelectActiveIdentityEndpoint(
    val supportedTokens: List<SupportedToken?>?,
)

@Serializable
data class SupportedToken(
    val accountSigninToken: AccountSigninToken?,
    val accountStateToken: AccountStateToken?,
    val datasyncIdToken: DatasyncIdToken?,
    val offlineCacheKeyToken: OfflineCacheKeyToken?,
    val pageIdToken: PageIdToken?,
)

@Serializable
data class AccountSigninToken(
    val signinUrl: String?,
)

@Serializable
data class AccountStateToken(
    val hasChannel: Boolean?,
    val isMerged: Boolean?,
    val obfuscatedGaiaId: String?,
)

@Serializable
data class DatasyncIdToken(
    val datasyncIdToken: String?,
)

@Serializable
data class OfflineCacheKeyToken(
    val clientCacheKey: String?,
)

@Serializable
data class PageIdToken(
    val pageId: String?,
)

/**
 * The outcome of asking YouTube which account a cookie belongs to.
 *
 * The three cases are kept apart on purpose: the sign-in screen used to learn about all three as
 * "no account", so a dropped request and a rejected cookie looked the same to the user and to the
 * log.
 */
sealed interface AccountSwitcherResult {
    /** YouTube answered with the accounts of the session. */
    data class Found(
        val accounts: List<AccountInfo>,
    ) : AccountSwitcherResult

    /** YouTube answered, but named no account: the cookie is not a signed-in session. */
    data class NoAccount(
        val httpStatus: Int,
        val code: String?,
    ) : AccountSwitcherResult

    /** The request did not get an answer. */
    data class Failed(
        val httpStatus: Int?,
        val reason: String,
    ) : AccountSwitcherResult
}

/**
 * The accounts of the signed-in session, read from the account-switcher response.
 *
 * The response nests the accounts four levels deep
 * (`data.contents[].accountSectionListRenderer.contents[].accountItemSectionRenderer.contents[]`).
 */
fun AccountSwitcherEndpointResponse.toListAccountInfo(): List<AccountInfo> {
    if (this.code != "SUCCESS" || this.data == null) return emptyList()

    return this.data.contents
        ?.firstOrNull()
        ?.accountSectionListRenderer
        ?.contents
        ?.firstOrNull()
        ?.accountItemSectionRenderer
        ?.contents
        ?.mapNotNull { content -> content?.accountItem }
        ?.mapNotNull { accountItem -> accountItem.toAccountInfo() }
        .orEmpty()
}

private fun AccountItem.toAccountInfo(): AccountInfo? =
    this.toAccountInfo(
        email =
            this.channelHandle
                ?.simpleText ?: "",
    )

/**
 * The skeleton of a JSON tree: the keys and the array indexes, never a value.
 *
 * The body of the account switcher holds the account of the user, so it cannot be logged. Its
 * shape can: when the answer names no account while the session is a session, the shape says
 * where the accounts went.
 */
fun jsonShape(
    element: JsonElement,
    maxDepth: Int = 12,
): String {
    fun render(
        current: JsonElement,
        depth: Int,
    ): String =
        when {
            depth > maxDepth -> "…"
            current is JsonObject ->
                current.entries.joinToString(",") { (key, value) ->
                    // A key that looks like an address is a value that landed in a key.
                    val name = if (key.contains('@')) "…" else key
                    "$name{${render(value, depth + 1)}}"
                }

            current is JsonArray ->
                current.firstOrNull()?.let { "[${render(it, depth + 1)}]" } ?: "[]"

            else -> "…"
        }

    return render(element, 0).take(600)
}

/**
 * Every account item in the response, wherever it sits.
 *
 * The account-switcher response nests the accounts four levels deep, and the path is not a
 * contract: a section added in front of the account section, or a wrapper added around it, leaves
 * the direct path empty and the sign-in is reported as "no account" although YouTube answered with
 * the account. The tree is searched for the account items themselves instead.
 */
fun extractAccountItems(
    root: JsonElement,
    json: Json,
): List<AccountInfo> {
    val items = mutableListOf<JsonObject>()

    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                (element["accountItem"] as? JsonObject)?.let { items.add(it) }
                element.forEach { (_, value) -> walk(value) }
            }

            is JsonArray -> element.forEach { walk(it) }
            else -> Unit
        }
    }
    walk(root)

    return items
        .mapNotNull { element -> runCatching { json.decodeFromJsonElement<AccountItem>(element) }.getOrNull() }
        .mapNotNull { it.toAccountInfo() }
        .distinctBy { it.name to it.email }
}
