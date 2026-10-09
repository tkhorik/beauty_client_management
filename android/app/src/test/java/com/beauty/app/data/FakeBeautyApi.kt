package com.beauty.app.data

import com.beauty.app.data.api.AcceptInviteLinkRequest
import com.beauty.app.data.api.AdminOrganizationDto
import com.beauty.app.data.api.CreateInviteLinkResponse
import com.beauty.app.data.api.InviteLinkDto
import com.beauty.app.data.api.InviteLinkPreviewResponse
import com.beauty.app.data.api.AdminUserDto
import com.beauty.app.data.api.ArchiveOrganizationRequest
import com.beauty.app.data.api.AuditEventDto
import com.beauty.app.data.api.AuthRequest
import com.beauty.app.data.api.AuthResponse
import com.beauty.app.data.api.BeautyApi
import com.beauty.app.data.api.ChangeMemberRoleRequest
import com.beauty.app.data.api.ChangePasswordRequest
import com.beauty.app.data.api.ClientDto
import com.beauty.app.data.api.CreateOrganizationCreationTokenRequest
import com.beauty.app.data.api.CreateOrganizationCreationTokenResponse
import com.beauty.app.data.api.CreateOrganizationRequest
import com.beauty.app.data.api.CreateVisitRequest
import com.beauty.app.data.api.ForgotPasswordRequest
import com.beauty.app.data.api.InviteMemberRequest
import com.beauty.app.data.api.JoinOrganizationRequest
import com.beauty.app.data.api.MemberDto
import com.beauty.app.data.api.OrganizationCreationTokenDto
import com.beauty.app.data.api.OrganizationDto
import com.beauty.app.data.api.RefreshRequest
import com.beauty.app.data.api.RegisterRequest
import com.beauty.app.data.api.ResetPasswordRequest
import com.beauty.app.data.api.UpdateClientRequest
import com.beauty.app.data.api.UpdateProfileRequest
import com.beauty.app.data.api.UpdateUserAdminRequest
import com.beauty.app.data.api.UserDto
import com.beauty.app.data.api.LanguagePreferenceRequest
import com.beauty.app.data.api.LanguagePreferenceResponse
import com.beauty.app.data.api.VisitAttachmentDto
import com.beauty.app.data.api.VisitDto
import com.beauty.app.data.api.VisitHistoryDto

/**
 * A [BeautyApi] where every method fails until a test overrides it.
 *
 * Tests previously implemented the interface inline, three times over, which
 * meant every new endpoint broke all of them at once and each had to be
 * repaired by hand. One base class here means adding a method costs a single
 * edit, and — more usefully — a test that accidentally calls an endpoint it did
 * not mean to gets a named error rather than a null or an empty list it might
 * quietly accept.
 */
abstract class FakeBeautyApi(private val reason: String = "not used in this test") : BeautyApi {
    override suspend fun login(request: AuthRequest): AuthResponse = error(reason)
    override suspend fun register(request: RegisterRequest): AuthResponse = error(reason)
    override suspend fun logout(request: RefreshRequest): Unit = error(reason)
    override suspend fun verifyEmail(token: String): Unit = error(reason)
    override suspend fun forgotPassword(request: ForgotPasswordRequest): Unit = error(reason)
    override suspend fun resetPassword(request: ResetPasswordRequest): Unit = error(reason)
    override suspend fun getClients(orgId: String): List<ClientDto> = error(reason)
    override suspend fun searchClients(orgId: String, query: String, tag: String?): List<ClientDto> = error(reason)
    override suspend fun createClient(orgId: String, request: UpdateClientRequest): ClientDto = error(reason)
    override suspend fun deleteClient(orgId: String, id: String): Unit = error(reason)
    override suspend fun uploadAttachment(orgId: String, visitId: String, tag: String, bytes: ByteArray, caption: String?): VisitAttachmentDto = error(reason)
    override suspend fun downloadAttachment(orgId: String, id: String): ByteArray = error(reason)
    override suspend fun updateClient(orgId: String, id: String, request: UpdateClientRequest): ClientDto = error(reason)
    override suspend fun createVisit(orgId: String, request: CreateVisitRequest): VisitDto = error(reason)
    override suspend fun getVisitsForClient(orgId: String, clientId: String): List<VisitHistoryDto> = error(reason)
    override suspend fun resendVerificationEmail(): Unit = error(reason)
    override suspend fun getCurrentUser(): UserDto = error(reason)
    override suspend fun updateProfile(request: UpdateProfileRequest): UserDto = error(reason)
    override suspend fun updateLanguagePreference(request: LanguagePreferenceRequest): LanguagePreferenceResponse = error(reason)
    override suspend fun changePassword(request: ChangePasswordRequest): AuthResponse = error(reason)
    override suspend fun getOrganizations(): List<OrganizationDto> = error(reason)
    override suspend fun createOrganization(request: CreateOrganizationRequest): OrganizationDto = error(reason)
    override suspend fun validateCreationToken(token: String): Boolean = error(reason)
    override suspend fun requestToJoinOrganization(request: JoinOrganizationRequest): OrganizationDto = error(reason)
    override suspend fun previewInviteLink(token: String): InviteLinkPreviewResponse = error(reason)
    override suspend fun acceptInviteLink(request: AcceptInviteLinkRequest): OrganizationDto = error(reason)
    override suspend fun getInviteLinks(orgId: String): List<InviteLinkDto> = error(reason)
    override suspend fun createInviteLink(orgId: String): CreateInviteLinkResponse = error(reason)
    override suspend fun revokeInviteLink(orgId: String, linkId: String): Unit = error(reason)
    override suspend fun getMembers(orgId: String): List<MemberDto> = error(reason)
    override suspend fun approveMember(orgId: String, userId: String): Unit = error(reason)
    override suspend fun declineMember(orgId: String, userId: String): Unit = error(reason)
    override suspend fun revokeMember(orgId: String, userId: String): Unit = error(reason)
    override suspend fun restoreMember(orgId: String, userId: String): Unit = error(reason)
    override suspend fun getOrganizationAudit(orgId: String, before: String?): List<AuditEventDto> = error(reason)
    override suspend fun inviteMember(orgId: String, request: InviteMemberRequest): Unit = error(reason)
    override suspend fun changeMemberRole(orgId: String, userId: String, request: ChangeMemberRoleRequest): Unit = error(reason)
    override suspend fun removeMember(orgId: String, userId: String): Unit = error(reason)
    override suspend fun getAdminUsers(): List<AdminUserDto> = error(reason)
    override suspend fun setUserSuspended(userId: String, request: UpdateUserAdminRequest): Unit = error(reason)
    override suspend fun getAdminOrganizations(): List<AdminOrganizationDto> = error(reason)
    override suspend fun archiveOrganization(id: String, request: ArchiveOrganizationRequest): Unit = error(reason)
    override suspend fun getCreationTokens(): List<OrganizationCreationTokenDto> = error(reason)
    override suspend fun createCreationToken(
        request: CreateOrganizationCreationTokenRequest
    ): CreateOrganizationCreationTokenResponse = error(reason)
    override suspend fun revokeCreationToken(id: String): Unit = error(reason)
}
