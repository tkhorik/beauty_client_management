package com.beauty.app.data

import com.beauty.app.data.api.AuthRequest
import com.beauty.app.data.api.AuthResponse
import com.beauty.app.data.api.BeautyApi
import com.beauty.app.data.api.ChangeMemberRoleRequest
import com.beauty.app.data.api.ChangePasswordRequest
import com.beauty.app.data.api.ClientDto
import com.beauty.app.data.api.CreateOrganizationRequest
import com.beauty.app.data.api.CreateVisitRequest
import com.beauty.app.data.api.ForgotPasswordRequest
import com.beauty.app.data.api.InviteMemberRequest
import com.beauty.app.data.api.JoinOrganizationRequest
import com.beauty.app.data.api.MemberDto
import com.beauty.app.data.api.OrganizationDto
import com.beauty.app.data.api.RefreshRequest
import com.beauty.app.data.api.RegisterRequest
import com.beauty.app.data.api.UpdateClientRequest
import com.beauty.app.data.api.UpdateProfileRequest
import com.beauty.app.data.api.UserDto
import com.beauty.app.data.api.VisitDto

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
    override suspend fun forgotPassword(request: ForgotPasswordRequest): Unit = error(reason)
    override suspend fun getClients(orgId: String): List<ClientDto> = error(reason)
    override suspend fun updateClient(orgId: String, id: String, request: UpdateClientRequest): ClientDto = error(reason)
    override suspend fun createVisit(orgId: String, request: CreateVisitRequest): VisitDto = error(reason)
    override suspend fun resendVerificationEmail(): Unit = error(reason)
    override suspend fun getCurrentUser(): UserDto = error(reason)
    override suspend fun updateProfile(request: UpdateProfileRequest): UserDto = error(reason)
    override suspend fun changePassword(request: ChangePasswordRequest): AuthResponse = error(reason)
    override suspend fun getOrganizations(): List<OrganizationDto> = error(reason)
    override suspend fun createOrganization(request: CreateOrganizationRequest): OrganizationDto = error(reason)
    override suspend fun requestToJoinOrganization(request: JoinOrganizationRequest): OrganizationDto = error(reason)
    override suspend fun getMembers(orgId: String): List<MemberDto> = error(reason)
    override suspend fun approveMember(orgId: String, userId: String): Unit = error(reason)
    override suspend fun inviteMember(orgId: String, request: InviteMemberRequest): Unit = error(reason)
    override suspend fun changeMemberRole(orgId: String, userId: String, request: ChangeMemberRoleRequest): Unit = error(reason)
    override suspend fun removeMember(orgId: String, userId: String): Unit = error(reason)
}
