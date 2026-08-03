package com.beauty.auth

/**
 * The system-wide privilege level of an account.
 *
 * Separate from [OrgRole] on purpose: this one has no organization to scope it
 * to, and collapsing the two into a single column is how "make this person an
 * admin of their salon" quietly turns into "give this person every salon".
 */
enum class GlobalRole {
    /** The normal case. All access comes from organization membership. */
    USER,

    /**
     * Unrestricted read/write across every organization.
     *
     * Granted only by direct database update or the `SUPER_ADMIN_EMAILS`
     * bootstrap in `AppSettings` — deliberately not through any API, because an
     * endpoint that can mint a super admin is an endpoint that can be abused
     * into minting one.
     */
    SUPER_ADMIN;

    companion object {
        /**
         * Parses a stored value, falling back to [USER].
         *
         * Fails closed: an unrecognised string (a typo, a value from a newer
         * version rolled back) must mean "no extra privilege", never "assume
         * the highest".
         */
        fun parse(raw: String?): GlobalRole =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: USER
    }
}

/** A user's capability *within one organization*. */
enum class OrgRole {
    /**
     * Manages membership — approves join requests, invites, removes members,
     * changes roles — and can do everything an [ORG_USER] can.
     */
    ORG_ADMIN,

    /** Reads and writes clients and visits belonging to the organization. Nothing else. */
    ORG_USER;

    companion object {
        /** Parses a stored or submitted value, falling back to the least privileged role. */
        fun parse(raw: String?): OrgRole =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: ORG_USER
    }
}

/**
 * Where a membership sits in the join handshake.
 *
 * Only [ACTIVE] grants access. The other three exist so that a request, an
 * invitation, or an admin's block can be recorded without conferring
 * anything, which is why every authorization query matches this column
 * explicitly rather than testing for the row's existence.
 */
enum class MembershipStatus {
    /** A full member. The only status that grants any access. */
    ACTIVE,

    /** The user asked to join and is waiting on an admin. Grants nothing. */
    PENDING,

    /** An admin asked the user to join and is waiting on them. Grants nothing. */
    INVITED,

    /**
     * An admin blocked this membership without removing it.
     *
     * Distinct from deletion (see [UserOrganizationsTable]'s note on why
     * removal has no tombstone): a suspension is meant to be temporary and
     * reversible by an admin, so the row — and its role, and its join date —
     * is worth keeping. Adding this value is safe by construction: every
     * existing authorization check already matches `== ACTIVE` explicitly
     * rather than testing row existence, so a membership newly in this state
     * is denied everywhere without touching a single existing check.
     */
    SUSPENDED;

    companion object {
        fun parse(raw: String?): MembershipStatus? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}
