import React from 'react';
import { PaymentStatus } from '../../models/payment';
import { Badge } from '../common/Badge';
import { CheckCircle2, Clock, XCircle, AlertTriangle, RotateCcw } from 'lucide-react';

interface PaymentStatusBadgeProps {
  status: PaymentStatus;
  className?: string;
}

export const PaymentStatusBadge: React.FC<PaymentStatusBadgeProps> = ({ status, className }) => {
  switch (status) {
    case 'CAPTURED':
      return (
        <Badge variant="success" className={className}>
          <CheckCircle2 className="w-3 h-3" />
          CAPTURED
        </Badge>
      );
    case 'AUTHORIZED':
      return (
        <Badge variant="info" className={className}>
          <Clock className="w-3 h-3" />
          AUTHORIZED
        </Badge>
      );
    case 'PROCESSING':
      return (
        <Badge variant="warning" className={className}>
          <span className="w-1.5 h-1.5 rounded-full bg-amber-400 animate-ping" />
          PROCESSING
        </Badge>
      );
    case 'CREATED':
      return (
        <Badge variant="default" className={className}>
          <Clock className="w-3 h-3" />
          CREATED
        </Badge>
      );
    case 'REFUND_PENDING':
      return (
        <Badge variant="purple" className={className}>
          <RotateCcw className="w-3 h-3" />
          REFUND PENDING
        </Badge>
      );
    case 'REFUNDED':
      return (
        <Badge variant="purple" className={className}>
          <RotateCcw className="w-3 h-3" />
          REFUNDED
        </Badge>
      );
    case 'PARTIALLY_REFUNDED':
      return (
        <Badge variant="purple" className={className}>
          <RotateCcw className="w-3 h-3" />
          PARTIALLY REFUNDED
        </Badge>
      );
    case 'DECLINED':
      return (
        <Badge variant="danger" className={className}>
          <AlertTriangle className="w-3 h-3" />
          DECLINED
        </Badge>
      );
    case 'FAILED':
      return (
        <Badge variant="danger" className={className}>
          <XCircle className="w-3 h-3" />
          FAILED
        </Badge>
      );
    default:
      return <Badge className={className}>{status}</Badge>;
  }
};
