package com.beauty.app.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.beauty.app.R

/** Only stable API codes, never backend prose, determine translated diagnostics. */
@Composable
fun localizedMessage(raw: String): String {
    val code = raw.substringBefore(':')
    val limit = raw.substringAfter(':', "").toIntOrNull()
    val id = when (code) {
        "EMAIL_REQUIRED" -> R.string.error_email_required
        "EMAIL_INVALID" -> R.string.error_email_invalid
        "EMAIL_TOO_LONG" -> R.string.error_email_too_long
        "NAME_REQUIRED" -> R.string.error_name_required
        "NAME_TOO_LONG" -> R.string.error_name_too_long
        "PASSWORD_REQUIRED" -> R.string.error_password_required
        "PASSWORD_TOO_SHORT" -> R.string.error_password_too_short
        "PASSWORD_TOO_LONG" -> R.string.error_password_too_long
        "ORGANIZATION_NAME_REQUIRED" -> R.string.error_organization_name_required
        "ORGANIZATION_NAME_TOO_LONG" -> R.string.error_organization_name_too_long
        "SLUG_REQUIRED" -> R.string.error_slug_required
        "SLUG_TOO_SHORT" -> R.string.error_slug_too_short
        "SLUG_TOO_LONG" -> R.string.error_slug_too_long
        "SLUG_INVALID" -> R.string.error_slug_invalid
        "SLUG_TAKEN" -> R.string.error_slug_taken
        "PASSWORD_UNCHANGED" -> R.string.error_password_unchanged
        "CURRENT_PASSWORD_INCORRECT" -> R.string.error_current_password_incorrect
        "EMAIL_ALREADY_EXISTS" -> R.string.error_email_already_exists
        "INVALID_CREDENTIALS" -> R.string.error_invalid_credentials
        "INVALID_RESET_TOKEN" -> R.string.error_invalid_reset_token
        "INVALID_VERIFICATION_TOKEN" -> R.string.error_invalid_verification_token
        "CREATION_TOKEN_INVALID" -> R.string.error_creation_token_invalid
        "LAST_ADMIN" -> R.string.error_last_admin
        "CANNOT_SUSPEND_SELF" -> R.string.error_cannot_suspend_self
        "ALREADY_A_MEMBER" -> R.string.error_already_a_member
        "ORGANIZATION_NOT_FOUND" -> R.string.error_organization_not_found
        "ACCOUNT_NOT_FOUND" -> R.string.error_account_not_found
        "MEMBERSHIP_SUSPENDED" -> R.string.error_membership_suspended
        "MEMBER_SUSPENDED" -> R.string.error_member_suspended
        "VALIDATION_FAILED" -> R.string.error_validation_failed
        "INVALID_LANGUAGE" -> R.string.error_invalid_language
        "SESSION_EXPIRED" -> R.string.error_session_expired
        "CLIENT_NOT_FOUND" -> R.string.error_client_not_found
        "VISIT_NOT_FOUND" -> R.string.error_visit_not_found
        "ATTACHMENT_NOT_FOUND" -> R.string.error_attachment_not_found
        "ACCESS_DENIED" -> R.string.error_access_denied
        "NETWORK_ERROR" -> R.string.error_network_error
        "ACTION_FAILED" -> R.string.error_action_failed
        "ORGANIZATION_ARCHIVED" -> R.string.organization_archived
        "TOO_MANY_ATTEMPTS" -> R.string.error_too_many_attempts
        "RESET_FAILED" -> R.string.error_reset_failed
        "LOGIN_FAILED" -> R.string.error_login_failed
        "REGISTER_FAILED" -> R.string.error_register_failed
        "PROFILE_FAILED" -> R.string.error_profile_failed
        "PASSWORD_CHANGE_FAILED" -> R.string.error_password_change_failed
        "REQUIRED_LINK" -> R.string.error_required_link
        "PASSWORD_MISMATCH" -> R.string.error_password_mismatch
        "CURRENT_PASSWORD_REQUIRED" -> R.string.error_current_password_required
        "VERIFY_FAILED" -> R.string.error_verify_failed
        "VERIFY_NETWORK" -> R.string.error_verify_network
        "EMAIL_CONFIRMED" -> R.string.error_email_confirmed
        "LINK_SENT" -> R.string.error_link_sent
        "REQUEST_APPROVED" -> R.string.error_request_approved
        "REQUEST_DECLINED" -> R.string.error_request_declined
        "MEMBER_REMOVED" -> R.string.error_member_removed
        "ROLE_UPDATED" -> R.string.error_role_updated
        "INVITATION_SENT" -> R.string.error_invitation_sent
        "LINK_REVOKED" -> R.string.error_link_revoked
        "LINK_ISSUED" -> R.string.error_link_issued
        "COULD_NOT_LOAD_MEMBERS" -> R.string.error_could_not_load_members
        "COULD_NOT_LOAD_ORGANIZATIONS" -> R.string.error_could_not_load_organizations
        "COULD_NOT_CREATE_ORGANIZATION" -> R.string.error_could_not_create_organization
        "COULD_NOT_SEND_REQUEST" -> R.string.error_could_not_send_request
        "COULD_NOT_ISSUE_LINK" -> R.string.error_could_not_issue_link
        "COULD_NOT_LOAD_ADMIN" -> R.string.error_could_not_load_admin
        "COULD_NOT_REFRESH_VISITS" -> R.string.error_could_not_refresh_visits
        "COULD_NOT_SAVE_ATTRIBUTES" -> R.string.error_could_not_save_attributes
        "COULD_NOT_DELETE_CLIENT" -> R.string.error_could_not_delete_client
        "ENTER_PROCEDURE_NOTES" -> R.string.error_enter_procedure_notes
        "COULD_NOT_READ_PHOTO" -> R.string.error_could_not_read_photo
        "COULD_NOT_SAVE_VISIT" -> R.string.error_could_not_save_visit
        "NO_CACHED_CLIENTS" -> R.string.error_no_cached_clients
        "INVALID_CREATE_LINK" -> R.string.error_invalid_create_link
        "CLIENT_NAME_REQUIRED" -> R.string.error_client_name_required
        "PHONE_REQUIRED" -> R.string.error_phone_required
        "DUPLICATE_FIELD" -> R.string.error_duplicate_field
        "DURATION_MIN" -> R.string.error_duration_min
        "DURATION_STEP" -> R.string.error_duration_step
        "UPDATE_CHECK" -> R.string.error_update_check
        "UPDATE_DOWNLOAD" -> R.string.error_update_download
        "ORGANIZATION_CREATED" -> R.string.organization_created
        "ORGANIZATION_JOINED" -> R.string.organization_joined
        "ORGANIZATION_REQUESTED" -> R.string.organization_requested
        "ACCOUNT_SUSPENDED" -> R.string.account_suspended
        "ACCOUNT_UNSUSPENDED" -> R.string.account_unsuspended
        "PENDING_UPLOADS" -> R.string.error_pending_uploads
        "SESSION_CHANGED" -> R.string.error_session_changed
        "PHOTO_UNAVAILABLE" -> R.string.error_photo_unavailable
        "RECORD_UNAVAILABLE" -> R.string.error_record_unavailable
        "RECORD_CONFLICT" -> R.string.error_record_conflict
        "PHOTO_TOO_LARGE" -> R.string.error_photo_too_large
        "PHOTO_UPLOAD_PENDING" -> R.string.error_photo_upload_pending
        "PHOTO_SAVED_UPLOAD_PENDING" -> R.string.photo_saved_upload_pending
        "COULD_NOT_SAVE_PHOTO" -> R.string.error_could_not_save_photo
        "MAX_USES_TOO_SMALL" -> R.string.error_max_uses_too_small
        "EXPIRY_TOO_SHORT" -> R.string.error_expiry_too_short
        "EXPIRY_TOO_LONG" -> R.string.error_expiry_too_long
        "ACCOUNT_CHANGED" -> R.string.error_account_changed
        "INVALID_TOKEN" -> R.string.error_invalid_token
        "EMAIL_NOT_VERIFIED" -> R.string.error_email_not_verified
        "MISSING_ORGANIZATION" -> R.string.error_missing_organization
        "NOT_A_MEMBER" -> R.string.error_not_a_member
        "ADMIN_REQUIRED" -> R.string.error_admin_required
        "SUPER_ADMIN_REQUIRED" -> R.string.error_super_admin_required
        "USER_NOT_FOUND" -> R.string.error_user_not_found
        "MISSING_ID" -> R.string.error_missing_id
        "ONE_FILE_REQUIRED" -> R.string.error_one_file_required
        "MISSING_ATTACHMENT_DATA" -> R.string.error_missing_attachment_data
        "ATTACHMENT_TOO_LARGE" -> R.string.error_attachment_too_large
        "LINK_NOT_FOUND" -> R.string.error_link_not_found
        "REQUEST_NOT_FOUND" -> R.string.error_request_not_found
        "JOIN_REQUEST_DECLINED" -> R.string.error_join_request_declined
        "ACCESS_REVOKED" -> R.string.notice_access_revoked
        "ACCESS_RESTORED" -> R.string.notice_access_restored
        "INVITE_LINK_INVALID" -> R.string.error_invite_link_invalid
        "INVITE_LINK_NOT_FOUND" -> R.string.error_invite_link_not_found
        "INVITE_LINK_CREATED" -> R.string.notice_invite_link_created
        "INVITE_LINK_REVOKED" -> R.string.notice_invite_link_revoked
        "CANNOT_REVOKE_SELF" -> R.string.error_cannot_revoke_self
        "MEMBERSHIP_NOT_SUSPENDED" -> R.string.error_membership_not_suspended
        "COULD_NOT_LOAD_ACTIVITY" -> R.string.error_could_not_load_activity
        "ORGANIZATION_MISMATCH" -> R.string.error_organization_mismatch
        "INTERNAL_ERROR" -> R.string.error_internal_error
        else -> R.string.error_action_failed
    }
    return when (id) {
        R.string.error_password_too_long -> stringResource(id, limit ?: 0)
        R.string.error_organization_name_too_long -> stringResource(id, limit ?: 0)
        R.string.error_slug_too_short -> stringResource(id, limit ?: 0)
        R.string.error_name_too_long -> stringResource(id, limit ?: 0)
        R.string.error_password_too_short -> stringResource(id, limit ?: 0)
        R.string.error_email_too_long -> stringResource(id, limit ?: 0)
        R.string.error_slug_too_long -> stringResource(id, limit ?: 0)
        R.string.error_duplicate_field, R.string.organization_created, R.string.organization_joined, R.string.organization_requested, R.string.account_suspended, R.string.account_unsuspended -> stringResource(id, raw.substringAfter(':', ""))
        R.string.error_expiry_too_long, R.string.error_duration_min, R.string.error_duration_step -> stringResource(id, limit ?: 15)
        else -> stringResource(id)
    }
}
