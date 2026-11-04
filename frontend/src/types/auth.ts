export interface CurrentUser {
  userId: number;
  employeeId: number | null;
  employeeCode: string | null;
  username: string;
  email: string;
  fullName: string;
  department: string | null;
  designation: string | null;
  office: string | null;
  reportingManager: string | null;
  profilePhotoUrl: string | null;
  roles: string[];
  permissions: string[];
  mustChangePassword: boolean;
}

export interface LoginRequest {
  usernameOrEmail: string;
  password: string;
  rememberMe: boolean;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  mustChangePassword: boolean;
  user: CurrentUser;
}

export interface TokenRefreshResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
}
