package com.filevault.storage;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface MaintenanceJobMapper {
    @Insert("INSERT INTO storage_audit_jobs(period_key) VALUES (#{period}) ON DUPLICATE KEY UPDATE id = id")
    void enqueue(long period);

    @Select("""
            SELECT id FROM storage_audit_jobs
            WHERE (state='PENDING' AND available_at <= UTC_TIMESTAMP(6))
               OR (state='RUNNING' AND lease_until <= UTC_TIMESTAMP(6))
            ORDER BY id LIMIT 6
            """)
    List<Long> due();

    @Update("""
            UPDATE storage_audit_jobs
            SET state='RUNNING', attempts=attempts+1, claim_token=#{token},
                lease_until=TIMESTAMPADD(SECOND, #{lease}, UTC_TIMESTAMP(6))
            WHERE id=#{id} AND attempts < #{maxAttempts}
              AND ((state='PENDING' AND available_at <= UTC_TIMESTAMP(6))
                   OR (state='RUNNING' AND lease_until <= UTC_TIMESTAMP(6)))
            """)
    int claim(long id, String token, int lease, int maxAttempts);

    @Update("""
            UPDATE storage_audit_jobs SET state='FAILED', finished_at=UTC_TIMESTAMP(6),
                lease_until=NULL, claim_token=NULL, error_kind='LeaseExpired'
            WHERE id=#{id} AND state='RUNNING' AND lease_until <= UTC_TIMESTAMP(6) AND attempts >= #{maxAttempts}
            """)
    void exhaust(long id, int maxAttempts);

    @Select("SELECT attempts FROM storage_audit_jobs WHERE id=#{id} AND claim_token=#{token}")
    Integer attempts(long id, String token);

    @Update("""
            UPDATE storage_audit_jobs SET state='SUCCEEDED', finished_at=UTC_TIMESTAMP(6),
                report=#{report}, error_kind=NULL, lease_until=NULL, claim_token=NULL
            WHERE id=#{id} AND state='RUNNING' AND claim_token=#{token}
            """)
    int succeed(long id, String token, String report);

    @Update("""
            UPDATE storage_audit_jobs SET state=#{state}, available_at=TIMESTAMPADD(SECOND, #{delay}, UTC_TIMESTAMP(6)),
                finished_at=IF(#{state}='FAILED', UTC_TIMESTAMP(6), NULL),
                error_kind=#{error}, lease_until=NULL, claim_token=NULL
            WHERE id=#{id} AND state='RUNNING' AND claim_token=#{token}
            """)
    int fail(long id, String token, String state, String error, long delay);
}
